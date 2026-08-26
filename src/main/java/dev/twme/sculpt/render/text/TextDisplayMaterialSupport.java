package dev.twme.sculpt.render.text;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.assets.model.BlockModel;
import dev.twme.sculpt.assets.model.ModelResolver;
import dev.twme.sculpt.core.BlockKey;

/**
 * Asynchronously resolves whether a vanilla block model can be rasterized by
 * the TextDisplay backend.
 *
 * <p>A successful {@link ModelResolver} result is the capability check: the
 * resolved model contains a full {@code 0..16} cube element, all six faces,
 * and resolvable textures. This includes every {@code cube_all} model while
 * also retaining equivalent explicit models such as {@code block/leaves}.
 */
public final class TextDisplayMaterialSupport {

    public enum ModelStatus {
        UNKNOWN,
        LOADING,
        OPAQUE,
        TRANSPARENT,
        UNSUPPORTED
    }

    public enum Status {
        /** No lookup has been requested; used by non-loading hot paths. */
        UNKNOWN,
        /** Model assets are being resolved away from the region thread. */
        LOADING,
        SUPPORTED,
        UNSUPPORTED;

        /**
         * Unknown-on-disk models start loading and may be used immediately:
         * the TextDisplay renderer composes its work onto the same future and
         * therefore cannot render before this lookup completes.
         */
        public boolean allowsOperation() {
            return this == LOADING || this == SUPPORTED;
        }
    }

    private final ConcurrentMap<BlockKey, ModelStatus> resolved =
        new ConcurrentHashMap<>();
    private final AsyncLoadingCache<BlockKey, Optional<BlockModel>> models;

    public TextDisplayMaterialSupport(
            final McAssetClient assets,
            final String minecraftVersion,
            final java.util.logging.Logger logger,
            final Executor executor) {
        final ModelResolver resolver = new ModelResolver(
            assets, logger, minecraftVersion, true);
        this.models = Caffeine.newBuilder()
            .maximumSize(512)
            .buildAsync((key, ignoredExecutor) -> CompletableFuture.supplyAsync(
                () -> resolver.resolve(key), executor)
                .whenComplete((model, failure) -> resolved.put(key,
                    classify(model, failure))));
    }

    public CompletableFuture<Optional<BlockModel>> resolve(final BlockKey key) {
        return models.get(key);
    }

    /**
     * Return the current result without ever waiting for disk or network I/O.
     * When {@code load} is true, an unknown model is scheduled asynchronously.
     */
    public Status status(final BlockKey key, final boolean load) {
        return switch (modelStatus(key, load)) {
            case UNKNOWN -> Status.UNKNOWN;
            case LOADING -> Status.LOADING;
            case OPAQUE, TRANSPARENT -> Status.SUPPORTED;
            case UNSUPPORTED -> Status.UNSUPPORTED;
        };
    }

    /** Return the cached transparency classification without blocking. */
    public ModelStatus modelStatus(final BlockKey key, final boolean load) {
        final ModelStatus known = resolved.get(key);
        if (known != null) return known;
        CompletableFuture<Optional<BlockModel>> future = models.getIfPresent(key);
        if (future == null) {
            if (!load) return ModelStatus.UNKNOWN;
            future = models.get(key);
        }
        if (!future.isDone()) return ModelStatus.LOADING;
        try {
            final ModelStatus completed = classify(future.join(), null);
            resolved.putIfAbsent(key, completed);
            return completed;
        } catch (final RuntimeException failure) {
            resolved.putIfAbsent(key, ModelStatus.UNSUPPORTED);
            return ModelStatus.UNSUPPORTED;
        }
    }

    public CompletableFuture<ModelStatus> resolveStatus(final BlockKey key) {
        return models.get(key).handle(TextDisplayMaterialSupport::classify);
    }

    private static ModelStatus classify(
            final Optional<BlockModel> model,
            final Throwable failure) {
        if (failure != null || model == null || model.isEmpty()) {
            return ModelStatus.UNSUPPORTED;
        }
        return model.orElseThrow().transparent()
            ? ModelStatus.TRANSPARENT : ModelStatus.OPAQUE;
    }

    public void clear() {
        models.synchronous().invalidateAll();
        resolved.clear();
    }
}
