package dev.twme.sculpt.assets.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP client for the mcasset.cloud raw-asset host with a per-version
 * on-disk cache.
 *
 * <p>URL pattern: {@code https://assets.mcasset.cloud/<version>/assets/minecraft/<path>}.
 * The {@code assets.} subdomain is required — {@code mcasset.cloud} alone
 * returns the browser UI page, not the file.
 *
 * <p>Cached files live under {@code <cacheRoot>/<version>/<path>}. ETag
 * handling is unnecessary because the version is pinned — once a file is
 * cached for {@code 1.21.11}, it stays valid forever.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.assets.fetch.McAssetClient}).
 */
public final class McAssetClient {

    private static final String HOST = "https://assets.mcasset.cloud";

    private final HttpClient http;
    private final Path cacheRoot;
    private final ConcurrentHashMap<Path, Object> cacheLocks =
            new ConcurrentHashMap<>();

    public McAssetClient(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetch the raw bytes for an asset path, caching to disk on first hit.
     * {@code assetPath} is everything after {@code assets/minecraft/} —
     * e.g. {@code blockstates/stone.json} or {@code textures/block/dirt.png}.
     */
    public byte[] fetch(String version, String assetPath) throws IOException {
        final Path cached = cachePath(version, assetPath)
            .toAbsolutePath().normalize();
        if (Files.isRegularFile(cached)) {
            return Files.readAllBytes(cached);
        }

        final Object lock = cacheLocks.computeIfAbsent(cached, ignored -> new Object());
        try {
            synchronized (lock) {
                if (Files.isRegularFile(cached)) {
                    return Files.readAllBytes(cached);
                }
                return fetchAndCache(version, assetPath, cached);
            }
        } finally {
            cacheLocks.remove(cached, lock);
        }
    }

    private byte[] fetchAndCache(
            final String version,
            final String assetPath,
            final Path cached) throws IOException {
        String url = HOST + "/" + version + "/assets/minecraft/" + assetPath;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Sculpt/1.0 (mcasset fetch)")
                .GET()
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching " + url, e);
        }
        if (resp.statusCode() == 404) {
            throw new AssetNotFoundException(url);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        Files.createDirectories(cached.getParent());
        final Path temporary = Files.createTempFile(
            cached.getParent(), ".sculpt-asset-", ".tmp");
        try {
            Files.write(temporary, resp.body());
            try {
                Files.move(temporary, cached,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return resp.body();
    }

    /** UTF-8 string convenience over {@link #fetch}. */
    public String fetchString(String version, String assetPath) throws IOException {
        return new String(fetch(version, assetPath), StandardCharsets.UTF_8);
    }

    /** Resolve the absolute cache path for an asset, whether or not it has been fetched. */
    public Path cachePath(String version, String assetPath) {
        return cacheRoot.resolve(version).resolve(assetPath);
    }

    /** Thrown when mcasset.cloud returns 404 — caller decides whether that's expected (e.g. variant model missing). */
    public static final class AssetNotFoundException extends IOException {
        public AssetNotFoundException(String url) {
            super("404 Not Found: " + url);
        }
    }
}
