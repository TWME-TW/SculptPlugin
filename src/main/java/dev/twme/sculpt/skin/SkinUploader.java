package dev.twme.sculpt.skin;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mineskin.ClientBuilder;
import org.mineskin.JsoupRequestHandler;
import org.mineskin.MineSkinClient;
import org.mineskin.data.SkinInfo;
import org.mineskin.data.Visibility;
import org.mineskin.exception.MineSkinRequestException;
import org.mineskin.options.GenerateQueueOptions;
import org.mineskin.request.GenerateRequest;
import org.mineskin.response.QueueResponse;

import dev.twme.sculpt.util.ExceptionSummary;

/**
 * Drives MineSkin uploads for {@link HeadSkin}s.
 *
 * <p>Uploads are submitted in batches of {@link #BATCH_SIZE}: we fan out
 * one batch, await every future in it, then start the next. The MineSkin
 * client still handles per-request rate-limiting inside a batch — batching
 * exists so a {@link Run#cancel()} call can reliably interrupt large
 * bake / upload sessions (only the current batch has been accepted by
 * the server; everything beyond never leaves the JVM).
 *
 * <p>State machine on each {@link HeadSkin}:
 * {@code PENDING → SUBMITTED (jobId) → COMPLETED} on success;
 * {@code PENDING → SUBMITTED → ERRORED} on rejection.
 * On plugin restart, callers can reset {@code SUBMITTED → PENDING} rather
 * than chasing job IDs across the restart — MineSkin's queue handles
 * duplicate uploads gracefully.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.skin.SkinUploader})
 * with two notable simplifications per {@code DEVELOPMENT_PLAN.md} §3.1:
 * <ul>
 *   <li>No Tessera-Paid backend / License code — Sculpt is a public
 *       plugin, uses standard MineSkin API + user-supplied
 *       {@code runtimeBaking.mineskin.apiKey}.</li>
 *   <li>No {@code JsoupRequestHandler} interceptor — the apiKey path
 *       goes through the default MineSkin transport.</li>
 * </ul>
 * MineSkin visibility defaults to {@code UNLISTED} so the uploads don't
 * pollute the public skin gallery (and can't be mistaken for someone
 * else's skin).
 */
public final class SkinUploader {

    public static final String DEFAULT_API_URL = "https://api.mineskin.org";

    private static final int BATCH_SIZE = 16;

    /** Max retries when MineSkin returns a rate-limit (429) response. */
    private static final int MAX_RATE_LIMIT_RETRIES = 5;

    /** Base delay in ms for exponential backoff after a 429. */
    private static final long RETRY_BASE_DELAY_MS = 1000L;

    private final Logger logger;
    private final String userAgent;
    private final long batchDelayMs;
    private MineSkinClient client;

    /** Active runs, keyed by run ID, so {@link #cancelAll()} can reach them. */
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    /**
     * @param apiKey  MineSkin API key, or {@code null}/blank to disable
     *                uploads. The constructor never throws on a bad key —
     *                a missing key just leaves the client un-initialised,
     *                and {@link #upload} fails every future with a
     *                descriptive reason.
     * @param batchDelayMs  Minimum delay in milliseconds between upload
     *                      batches. 0 means no delay. The MineSkin free tier
     *                      limits to 180 requests/minute, so with
     *                      {@link #BATCH_SIZE}=16 a delay of ~6000 ms keeps
     *                      the average rate safely under the limit.
     *                      Individual 429 responses are also retried with
     *                      exponential backoff regardless of this delay.
     */
    public SkinUploader(Logger logger, String userAgent, String apiKey,
                        long batchDelayMs) {
        this(logger, userAgent, apiKey, DEFAULT_API_URL, batchDelayMs);
    }

    /**
     * @param apiUrl MineSkin-compatible HTTP(S) API base URL
     */
    public SkinUploader(Logger logger, String userAgent, String apiKey,
                        String apiUrl, long batchDelayMs) {
        this.logger = logger;
        this.userAgent = userAgent;
        this.batchDelayMs = Math.max(0, batchDelayMs);
        rebuildClient(apiKey, normalizeApiUrl(apiUrl));
    }

    public boolean isReady() {
        return client != null;
    }

    private void rebuildClient(String apiKey, String apiUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            logger.warning("[SkinUploader] runtimeBaking.mineskin.apiKey is empty;"
                + " uploads disabled");
            this.client = null;
            return;
        }
        try {
            // The 3.2.6-SNAPSHOT API of ClientBuilder requires an explicit
            // RequestHandlerConstructor before build() is callable. We use
            // the JsoupRequestHandler from the java-client-jsoup artifact
            // since it matches the RequestHandlerConstructor signature
            // (String, String, String, int, Gson).
            this.client = ClientBuilder.create()
                    .baseUrl(apiUrl)
                    .userAgent(userAgent)
                    .apiKey(apiKey)
                    .requestHandler(JsoupRequestHandler::new)
                    .generateQueueOptions(GenerateQueueOptions.createAuto())
                    .build();
        } catch (Throwable t) {
            ExceptionSummary.log(logger, Level.SEVERE,
                    "[SkinUploader] Failed to initialize MineSkin client; uploads disabled", t);
            this.client = null;
        }
    }

    public static String normalizeApiUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) return DEFAULT_API_URL;
        try {
            URI uri = new URI(apiUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("https")
                    || scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw invalidApiUrl(apiUrl);
            }
            String normalized = uri.toString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (URISyntaxException e) {
            throw invalidApiUrl(apiUrl);
        }
    }

    private static IllegalArgumentException invalidApiUrl(String apiUrl) {
        return new IllegalArgumentException(
                "runtimeBaking.mineskin.apiUrl must be an absolute HTTP(S) base URL without"
                        + " credentials, query, or fragment; got " + apiUrl);
    }

    /**
     * Upload all heads. Returns a {@link Run} so the caller can cancel or
     * await completion. Each successful upload triggers {@code onComplete}
     * synchronously on the upload thread — the caller is responsible for
     * scheduling Bukkit work on the main thread if needed.
     */
    public Run upload(List<HeadSkin> heads, Path pngBaseDir, Consumer<HeadSkin> onComplete) {
        Run run = new Run();
        runs.put(run.id, run);

        if (client == null) {
            run.future.completeExceptionally(
                    new IllegalStateException("MineSkin client not initialized - set"
                        + " runtimeBaking.mineskin.apiKey in config.yml"));
            runs.remove(run.id);
            return run;
        }

        List<HeadSkin> pending = new ArrayList<>();
        for (HeadSkin h : heads) {
            if (h.state() == SkinState.PENDING || h.state() == SkinState.ERRORED) {
                pending.add(h);
            } else if (h.state() == SkinState.SUBMITTED) {
                // Restart-recovery: SUBMITTED across a restart means we lost
                // the awaiter. Resubmit; MineSkin de-dupes server-side.
                h.state(SkinState.PENDING);
                h.jobId(null);
                pending.add(h);
            }
        }

        run.future = pending.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : runBatch(run, pending, 0, pngBaseDir, onComplete);

        run.future.whenComplete((v, ex) -> {
            runs.remove(run.id);
            if (ex != null && !run.cancelled.get()) {
                ExceptionSummary.log(logger, Level.WARNING,
                        "[SkinUploader] Upload run " + run.id + " failed", ex);
            }
        });

        return run;
    }

    private CompletableFuture<Void> runBatch(Run run, List<HeadSkin> pending, int offset,
                                             Path pngBaseDir, Consumer<HeadSkin> onComplete) {
        if (run.cancelled.get() || offset >= pending.size()) {
            return CompletableFuture.completedFuture(null);
        }
        int end = Math.min(offset + BATCH_SIZE, pending.size());
        List<CompletableFuture<Void>> batch = new ArrayList<>(end - offset);
        for (int i = offset; i < end; i++) {
            if (run.cancelled.get()) break;
            CompletableFuture<Void> f = uploadOne(run, pending.get(i), pngBaseDir, onComplete);
            run.futures.add(f);
            batch.add(f);
        }
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(batch.toArray(new CompletableFuture[0]))
                .thenCompose(v -> {
                    if (run.cancelled.get() || end >= pending.size()) {
                        return runBatch(run, pending, end, pngBaseDir, onComplete);
                    }
                    // Respect MineSkin rate limit: insert a delay between batches
                    // so we don't exceed 180 req/minute (default ~6s between batches
                    // of 16 stays safely under the limit).
                    if (batchDelayMs > 0) {
                        return CompletableFuture.supplyAsync(
                                        () -> null,
                                        CompletableFuture.delayedExecutor(batchDelayMs, TimeUnit.MILLISECONDS))
                                .thenCompose(ignored ->
                                        runBatch(run, pending, end, pngBaseDir, onComplete));
                    }
                    return runBatch(run, pending, end, pngBaseDir, onComplete);
                });
    }

    /**
     * Upload one head, retrying if MineSkin responds with a rate-limit (429).
     * Uses exponential backoff ({@value #RETRY_BASE_DELAY_MS}ms × 2^attempt)
     * up to {@value #MAX_RATE_LIMIT_RETRIES} retries before marking the head
     * as {@link SkinState#ERRORED}.
     */
    private CompletableFuture<Void> uploadOne(Run run, HeadSkin head, Path pngBaseDir,
                                              Consumer<HeadSkin> onComplete) {
        if (run.cancelled.get()) return CompletableFuture.completedFuture(null);
        Path png = head.pngFile() == null ? null : pngBaseDir.resolve(head.pngFile());
        if (png == null || !png.toFile().isFile()) {
            logger.warning("Skipping head " + head.id() + " - PNG missing at " + png);
            head.state(SkinState.ERRORED);
            return CompletableFuture.completedFuture(null);
        }
        File pngFile = png.toFile();
        GenerateRequest request = GenerateRequest.upload(pngFile)
                .name("sculpt")
                .visibility(Visibility.UNLISTED);

        return uploadWithRetry(run, head, request, onComplete, 0);
    }

    /**
     * Recursive async retry wrapper around the MineSkin submit-and-await flow.
     * On rate-limit (429) we delay for {@code RETRY_BASE_DELAY_MS * 2^attempt}
     * and retry, up to {@link #MAX_RATE_LIMIT_RETRIES} times. All other errors,
     * or exhausting retries, fall through to {@link #onFailure}.
     */
    private CompletableFuture<Void> uploadWithRetry(Run run, HeadSkin head,
                                                    GenerateRequest request,
                                                    Consumer<HeadSkin> onComplete,
                                                    int attempt) {
        if (run.cancelled.get()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = client.queue().submit(request)
                .thenCompose(qr -> {
                    if (run.cancelled.get()) {
                        return CompletableFuture.failedFuture(
                                new CancellationException("cancelled"));
                    }
                    return onSubmitted(head, qr);
                })
                .thenAccept(skin -> {
                    if (run.cancelled.get()) return;
                    applySkin(head, skin);
                    if (onComplete != null) onComplete.accept(head);
                });

        // Wrap in a future that retries on 429. IMPORTANT: the result future
        // ALWAYS completes normally (null) so that CompletableFuture.allOf
        // in runBatch doesn't abort the entire batch when one head fails.
        // Errored heads are handled via onFailure / SkinState.ERRORED and
        // simply won't be registered in the registry at the end.
        CompletableFuture<Void> result = new CompletableFuture<>();
        future.whenComplete((v, ex) -> {
            if (ex == null) {
                result.complete(v);
                return;
            }
            if (isCancellation(ex) || run.cancelled.get()) {
                // Cancellation is deliberate — still complete normally so
                // the rest of the batch isn't disrupted.
                result.complete(null);
                return;
            }
            if (isRateLimitError(ex) && attempt < MAX_RATE_LIMIT_RETRIES) {
                long delay = RETRY_BASE_DELAY_MS * (1L << attempt); // 1s, 2s, 4s, 8s, 16s
                logger.info("Rate limited on head " + head.id()
                        + ", retrying in " + delay + "ms"
                        + " (attempt " + (attempt + 1) + "/" + MAX_RATE_LIMIT_RETRIES + ")");
                CompletableFuture
                        .delayedExecutor(delay, TimeUnit.MILLISECONDS)
                        .execute(() -> {
                            uploadWithRetry(run, head, request, onComplete, attempt + 1)
                                    .whenComplete((r, t) -> {
                                        result.complete(null); // always complete normally
                                    });
                        });
            } else {
                onFailure(head, ex);
                result.complete(null); // swallow error — batch continues
            }
        });
        return result;
    }

    /**
     * True if any exception in the causal chain wraps a MineSkin 429
     * (rate-limit) response.
     */
    private static boolean isRateLimitError(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof MineSkinRequestException mre) {
                String msg = mre.getMessage();
                if (msg != null && (msg.contains("rate limit")
                        || msg.contains("Rate limit")
                        || msg.contains("429"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private CompletableFuture<SkinInfo> onSubmitted(HeadSkin head, QueueResponse qr) {
        try {
            head.jobId(qr.getJob().id());
            head.state(SkinState.SUBMITTED);
        } catch (Throwable t) {
            ExceptionSummary.log(logger, Level.WARNING,
                    "[SkinUploader] Failed to record job id for head " + head.id(), t);
        }
        return qr.getJob().waitForCompletion(client)
                .thenCompose(ref -> ref.getOrLoadSkin(client));
    }

    private void applySkin(HeadSkin head, SkinInfo skin) {
        try {
            head.texture(
                    skin.texture().data().value(),
                    skin.texture().data().signature(),
                    skin.uuid());
            head.state(SkinState.COMPLETED);
        } catch (Throwable t) {
            ExceptionSummary.log(logger, Level.WARNING,
                    "[SkinUploader] Failed to extract texture data for head " + head.id(), t);
            head.state(SkinState.ERRORED);
        }
    }

    private void onFailure(HeadSkin head, Throwable ex) {
        ExceptionSummary.log(logger, Level.WARNING,
                "[SkinUploader] Head " + head.id() + " upload failed", ex);
        head.state(SkinState.ERRORED);
    }

    private static boolean isCancellation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof CancellationException) return true;
        }
        return false;
    }

    public void cancelAll() {
        for (Run r : runs.values()) r.cancel();
    }

    public static final class Run {
        private static final AtomicLong SEQ = new AtomicLong();

        public final String id = "run-" + SEQ.incrementAndGet();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final List<CompletableFuture<Void>> futures =
                Collections.synchronizedList(new ArrayList<>());
        private CompletableFuture<Void> future = CompletableFuture.completedFuture(null);

        public CompletableFuture<Void> future() { return future; }

        public void cancel() {
            cancelled.set(true);
            synchronized (futures) {
                for (CompletableFuture<Void> f : futures) f.cancel(true);
            }
        }
    }
}
