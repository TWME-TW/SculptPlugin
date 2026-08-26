package dev.twme.sculpt.skin.bake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.assets.model.BlockModel;
import dev.twme.sculpt.assets.model.ModelResolver;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.ChunkSpec;
import dev.twme.sculpt.skin.HeadSkin;
import dev.twme.sculpt.skin.HeadSkinPacker;
import dev.twme.sculpt.skin.HeadsRegistry;
import dev.twme.sculpt.skin.SkinAssembler;
import dev.twme.sculpt.skin.SkinDiskCache;
import dev.twme.sculpt.skin.SkinState;
import dev.twme.sculpt.skin.SkinUploader;
import dev.twme.sculpt.skin.TileRotations;
import dev.twme.sculpt.split.TextureSplitter;
import dev.twme.sculpt.util.ExceptionSummary;

/**
 * Bakes a local 2x2x2 cell batch on demand: resolve assets, split the
 * requested textures, pack heads, upload to MineSkin, then merge them into
 * {@link HeadsRegistry}. Used both by the runtime listener (when a player
 * breaks a block we haven't baked yet) and the SQLite-only {@link BakeMain} CLI.
 *
 * <p>Concurrency: dedups simultaneous requests for the same block and sibling
 * batch via {@link #inflight}. Separate areas of a high-resolution grid can
 * proceed independently without generating the entire N-cubed lattice.
 *
 * <p>Cache bypass: when the global "stale" flag in
 * {@link TileRotations#consumeStale} is set, the next bake
 * for any block tells {@link SkinUploader} to skip its skin-hash cache and
 * upload fresh PNGs after changing in-plane rotation.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.skin.bake.BlockBaker}).
 */
public final class BlockBaker {

    private static final long FAILURE_BACKOFF_BASE_SECONDS = 30L;
    private static final long FAILURE_BACKOFF_MAX_SECONDS = 600L;
    private static final long MAX_FAILED_BATCHES = 4096L;
    private static final CompletableFuture<Boolean> BACKOFF_RESULT =
            CompletableFuture.completedFuture(false);

    private final Logger logger;
    private final BooleanSupplier debug;
    private final ModelResolver resolver;
    private final TextureSplitter splitter;
    private final HeadSkinPacker packer;
    private final SkinAssembler assembler;
    private final SkinUploader uploader;
    private final HeadsRegistry registry;
    private final SkinDiskCache diskCache;
    private final Path pngDir;
    private final Executor executor;

    private final Map<Batch, CompletableFuture<Boolean>> inflight = new ConcurrentHashMap<>();
    private final Cache<Batch, FailureBackoff> failedBatches = Caffeine.newBuilder()
            .maximumSize(MAX_FAILED_BATCHES)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    /** How long to wait for the entire upload batch to complete. */
    private final long uploadTimeoutMinutes;

    /**
     * Bake-plan summary fired through the optional callback passed to
     * {@link #bake(BakeKey, Consumer)} once the splitter and packer have
     * decided what's actually going to happen. {@code needUpload} is the
     * subset of {@code uniqueHeads} that missed both the in-memory registry
     * and the persistent disk cache and will hit MineSkin.
     */
    public record Plan(int totalChunks, int uniqueHeads, int needUpload) {}

    /** A 2x2x2 sibling group identified by its even-coordinate origin. */
    public record Batch(BakeKey key, int gridN, ChunkCoord parentOrigin) {
        public Batch {
            if (key == null) throw new IllegalArgumentException("key must not be null");
            if (gridN < 2) throw new IllegalArgumentException("gridN must be >= 2");
            if (parentOrigin == null) {
                throw new IllegalArgumentException("parentOrigin must not be null");
            }
            if ((parentOrigin.x() & 1) != 0
                    || (parentOrigin.y() & 1) != 0
                    || (parentOrigin.z() & 1) != 0
                    || parentOrigin.x() + 1 >= gridN
                    || parentOrigin.y() + 1 >= gridN
                    || parentOrigin.z() + 1 >= gridN) {
                throw new IllegalArgumentException("Invalid 2x2x2 batch origin for gridN="
                        + gridN + ": " + parentOrigin.asKey());
            }
        }

        public static Batch containing(BakeKey key, int gridN, ChunkCoord target) {
            if (target == null
                    || target.x() >= gridN
                    || target.y() >= gridN
                    || target.z() >= gridN) {
                throw new IllegalArgumentException("Target outside gridN=" + gridN + ": " + target);
            }
            return new Batch(key, gridN, new ChunkCoord(
                    target.x() & ~1, target.y() & ~1, target.z() & ~1));
        }

        public List<ChunkCoord> cells() {
            List<ChunkCoord> cells = new ArrayList<>(8);
            for (int y = parentOrigin.y(); y <= parentOrigin.y() + 1; y++) {
                for (int z = parentOrigin.z(); z <= parentOrigin.z() + 1; z++) {
                    for (int x = parentOrigin.x(); x <= parentOrigin.x() + 1; x++) {
                        cells.add(new ChunkCoord(x, y, z));
                    }
                }
            }
            return List.copyOf(cells);
        }

        public String label() {
            return parentOrigin.asKey() + ".."
                    + (parentOrigin.x() + 1) + ","
                    + (parentOrigin.y() + 1) + ","
                    + (parentOrigin.z() + 1);
        }
    }

    private final BiConsumer<Batch, Boolean> postBakeCallback;

    public BlockBaker(Logger logger,
                      BooleanSupplier debug,
                      McAssetClient assets,
                      String mcVersion,
                      HeadsRegistry registry,
                      SkinUploader uploader,
                      SkinDiskCache diskCache,
                      Path pngDir,
                      Executor executor,
                      long uploadTimeoutMinutes,
                      BiConsumer<Batch, Boolean> postBakeCallback) {
        this.logger = logger;
        this.debug = debug;
        this.resolver = new ModelResolver(assets, logger, mcVersion);
        this.splitter = new TextureSplitter();
        this.packer = new HeadSkinPacker();
        this.assembler = new SkinAssembler();
        this.uploader = uploader;
        this.registry = registry;
        this.diskCache = diskCache;
        this.pngDir = pngDir;
        this.executor = executor;
        this.uploadTimeoutMinutes = Math.max(1, uploadTimeoutMinutes);
        this.postBakeCallback = postBakeCallback;
    }

    public CompletableFuture<Boolean> bake(BakeKey key) {
        return bake(key, new ChunkCoord(0, 0, 0), null);
    }

    /**
     * Bake the sibling group containing {@code target}.
     */
    public CompletableFuture<Boolean> bake(BakeKey key, ChunkCoord target) {
        return bake(key, target, null);
    }

    /** Build the stable batch identity used by pending-render tracking. */
    public Batch batchFor(BakeKey key, ChunkCoord target) {
        int gridN = registry == null ? 2 : registry.gridN();
        return Batch.containing(key, gridN, target);
    }

    /** Bake a previously computed batch. */
    public CompletableFuture<Boolean> bake(Batch batch) {
        return bake(batch, null);
    }

    /**
     * Like {@link #bake(BakeKey)} but invokes {@code onPlan} as soon as the
     * splitter/packer/cache lookup finishes and we know how many MineSkin
     * uploads this bake actually needs. {@code onPlan} runs on the bake
     * executor thread, so dispatch to the main thread inside it if it touches
     * Bukkit state. Skipped entirely when an inflight bake for the same batch
     * is already running (the second caller just rides the existing future).
     *
     * <p>For untinted blocks, callers should pass {@link BakeKey#untinted(BlockKey)}.
     */
    public CompletableFuture<Boolean> bake(BakeKey key, Consumer<Plan> onPlan) {
        return bake(key, new ChunkCoord(0, 0, 0), onPlan);
    }

    public CompletableFuture<Boolean> bake(
            BakeKey key, ChunkCoord target, Consumer<Plan> onPlan) {
        return bake(batchFor(key, target), onPlan);
    }

    private CompletableFuture<Boolean> bake(Batch batch, Consumer<Plan> onPlan) {
        if (registry != null && batch.gridN() != registry.gridN()) {
            throw new IllegalArgumentException("Batch gridN=" + batch.gridN()
                    + " does not match baker gridN=" + registry.gridN());
        }
        if (isBackingOff(batch)) {
            if (postBakeCallback != null) {
                postBakeCallback.accept(batch, false);
            }
            return BACKOFF_RESULT;
        }
        CompletableFuture<Boolean> future = inflight.computeIfAbsent(
                batch, request -> startBake(request, onPlan));
        // Attach cleanup after computeIfAbsent publishes the future. This also
        // handles very fast failures that complete inside the mapping call.
        future.whenComplete((success, error) -> inflight.remove(batch, future));
        // Attach completion per caller, not only when the underlying work is
        // first created. A waiter that arrives during the final registry write
        // is therefore still drained even if the shared future just completed.
        if (postBakeCallback != null) {
            future.whenComplete((success, error) -> postBakeCallback.accept(
                    batch, error == null && Boolean.TRUE.equals(success)));
        }
        return future;
    }

    private CompletableFuture<Boolean> startBake(Batch batch, Consumer<Plan> onPlan) {
        boolean bypassCache = TileRotations.consumeStale();
        CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> {
            try {
                boolean succeeded = doBake(batch, bypassCache, onPlan);
                recordResult(batch, succeeded);
                return succeeded;
            } catch (Exception e) {
                ExceptionSummary.log(logger, Level.WARNING,
                        "[runtime-bake] " + batch.key() + " gridN=" + batch.gridN()
                                + " batch=" + batch.label() + " failed", e);
                recordResult(batch, false);
                return false;
            }
        }, executor);
        return f;
    }

    private boolean isBackingOff(Batch batch) {
        FailureBackoff failure = failedBatches.getIfPresent(batch);
        return failure != null
                && System.nanoTime() - failure.retryAfterNanos() < 0;
    }

    private void recordResult(Batch batch, boolean succeeded) {
        if (succeeded) {
            failedBatches.invalidate(batch);
            return;
        }
        failedBatches.asMap().compute(batch, (ignored, previous) -> {
            int failures = previous == null ? 1 : previous.failures() + 1;
            int exponent = Math.min(failures - 1, 30);
            long delaySeconds = Math.min(
                    FAILURE_BACKOFF_MAX_SECONDS,
                    FAILURE_BACKOFF_BASE_SECONDS << exponent);
            return new FailureBackoff(failures,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(delaySeconds));
        });
    }

    private record FailureBackoff(int failures, long retryAfterNanos) {}

    private boolean doBake(Batch batch, boolean bypassCache, Consumer<Plan> onPlan)
            throws IOException, ExecutionException, InterruptedException, TimeoutException {

        BakeKey key = batch.key();
        logger.info(attemptMessage(batch));

        List<ChunkCoord> missingCoords = new ArrayList<>(8);
        if (registry != null) {
            for (ChunkCoord coord : batch.cells()) {
                Optional<HeadsRegistry.Entry> existing = registry.get(key, coord);
                if (existing.isEmpty() || !isRenderable(existing.get())) {
                    missingCoords.add(coord);
                }
            }
            if (missingCoords.isEmpty()) {
                logger.info("[runtime-bake] " + key + " gridN=" + batch.gridN()
                        + " batch=" + batch.label() + " already available");
                return true;
            }
        } else {
            missingCoords.addAll(batch.cells());
        }

        if (uploader == null || !uploader.isReady()) {
            logger.warning("[runtime-bake] " + key
                + " skipped — no MineSkin client (set runtimeBaking.mineskin.apiKey)");
            return false;
        }

        Optional<BlockModel> modelOpt = resolver.resolve(key.block());
        if (modelOpt.isEmpty()) {
            if (debug.getAsBoolean()) logger.info("[runtime-bake] " + key + " unsupported (non-cube or asset missing)");
            return false;
        }
        BlockModel model = modelOpt.get();
        if (model.tinted()) {
            if (key.tintArgb() == 0) {
                if (debug.getAsBoolean()) logger.info("[runtime-bake] " + key + " skipped (tinted block, no tint provided)");
                return false;
            }
            model = model.withTint(key.tintArgb());
        }

        int gridN = registry.gridN();
        List<ChunkSpec> chunks = splitter.split(model, gridN, missingCoords);
        HeadSkinPacker.Result packed = packer.pack(chunks);
        logger.info("[runtime-bake] " + key + " — " + chunks.size() + " chunks → "
                + packed.uniqueHeads().size() + " unique heads"
                + (bypassCache ? " (cache bypassed)" : ""));

        List<HeadSkin> needUpload = new java.util.ArrayList<>();
        java.util.Map<HeadSkin, String> pngHashByHead = new java.util.HashMap<>();
        for (HeadSkin head : packed.uniqueHeads()) {
            HeadsRegistry.Entry registryHit = bypassCache ? null : registry.findByHash(head.contentHash());
            if (registryHit != null && isRenderable(registryHit)) {
                head.texture(registryHit.textureValue(), registryHit.textureSignature(), registryHit.mineskinUuid());
                head.state(SkinState.COMPLETED);
                continue;
            }
            java.nio.file.Path pngPath = assembler.assemble(head, pngDir);
            byte[] pngBytes = java.nio.file.Files.readAllBytes(pngPath);
            String pngHash = SkinDiskCache.hashPng(pngBytes);
            pngHashByHead.put(head, pngHash);
            SkinDiskCache.Entry diskHit = diskCache == null ? null : diskCache.find(pngHash);
            if (diskHit != null && isRenderable(diskHit)) {
                head.texture(diskHit.value(), diskHit.signature(), diskHit.uuid());
                head.state(SkinState.COMPLETED);
                continue;
            }
            needUpload.add(head);
        }

        if (onPlan != null) {
            try {
                onPlan.accept(new Plan(chunks.size(), packed.uniqueHeads().size(), needUpload.size()));
            } catch (RuntimeException re) {
                logger.warning("[runtime-bake] " + key + " onPlan callback threw: " + re.getMessage());
            }
        }

        if (!needUpload.isEmpty()) {
            logger.info("[runtime-bake] " + key + " uploading " + needUpload.size()
                    + " new skins ("
                    + (packed.uniqueHeads().size() - needUpload.size())
                    + " hit cache)");
            SkinUploader.Run run = uploader.upload(needUpload, pngDir.getParent(), h -> {
                if (diskCache != null && h.state() == SkinState.COMPLETED) {
                    String pngHash = pngHashByHead.get(h);
                    if (pngHash != null) {
                        diskCache.put(pngHash, h.textureValue(), h.textureSignature(), h.mineskinUuid());
                    }
                }
            });
            run.future().get(uploadTimeoutMinutes, TimeUnit.MINUTES);
        }

        Map<ChunkCoord, HeadsRegistry.Entry> chunkMap = new LinkedHashMap<>();
        int completedHeads = 0, erroredHeads = 0;
        for (HeadSkin head : packed.uniqueHeads()) {
            if (head.state() == SkinState.COMPLETED) completedHeads++;
            else erroredHeads++;
        }
        packed.chunkToHead().forEach((chunk, head) -> {
            if (head.state() == SkinState.COMPLETED) {
                chunkMap.put(chunk.coord(), new HeadsRegistry.Entry(
                        head.contentHash(), head.textureValue(),
                        head.textureSignature(), head.mineskinUuid()));
            }
        });
        logger.info("[runtime-bake] " + key + " complete: "
                + completedHeads + "/" + packed.uniqueHeads().size() + " heads succeeded, "
                + chunkMap.size() + "/" + chunks.size() + " chunks registered"
                + (erroredHeads > 0 ? " (" + erroredHeads + " heads errored - retry to fill in)" : ""));

        if (chunkMap.isEmpty()) {
            logger.warning("[runtime-bake] " + key + " produced 0 completed chunks");
            return false;
        }

        // Keep each requested batch atomic. Successful uploads are already in
        // SkinDiskCache, so a retry only needs to upload the failed hashes.
        if (chunkMap.size() < chunks.size()) {
            logger.warning("[runtime-bake] " + key + " incomplete ("
                    + chunkMap.size() + "/" + chunks.size()
                    + " chunks); leaving unregistered so the next bake retries the failures.");
            return false;
        }

        registry.registerPartial(key, chunkMap, model.variantRotations());

        Files.createDirectories(pngDir);
        return true;
    }

    private static boolean isRenderable(HeadsRegistry.Entry entry) {
        return entry.textureValue() != null && !entry.textureValue().isEmpty()
                && entry.textureSignature() != null && !entry.textureSignature().isEmpty();
    }

    private static boolean isRenderable(SkinDiskCache.Entry entry) {
        return entry.value() != null && !entry.value().isEmpty()
                && entry.signature() != null && !entry.signature().isEmpty();
    }

    private static String attemptMessage(Batch batch) {
        BakeKey key = batch.key();
        StringBuilder message = new StringBuilder("[runtime-bake] attempting block=")
                .append(key.block());
        if (key.isTinted()) {
            message.append('#')
                    .append(String.format("%06X", key.tintArgb() & 0xFFFFFF));
        }
        return message.append(", gridN=").append(batch.gridN())
                .append(", batch=").append(batch.label())
                .toString();
    }
}
