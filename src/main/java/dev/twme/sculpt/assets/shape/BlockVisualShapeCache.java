package dev.twme.sculpt.assets.shape;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;

/** Bounded asynchronous cache for complete BlockData visual shapes. */
public final class BlockVisualShapeCache {

    private final AsyncLoadingCache<String, BlockVisualShapeResolver.Resolution> cache;
    private final Executor executor;

    public BlockVisualShapeCache(
            final BlockVisualShapeResolver resolver,
            final Executor executor) {
        this.executor = executor;
        cache = Caffeine.newBuilder()
            .maximumSize(2_048)
            .buildAsync((blockData, ignored) -> CompletableFuture.supplyAsync(
                () -> resolver.resolve(blockData), executor));
    }

    public CompletableFuture<BlockVisualShapeResolver.Resolution> resolve(
            final String blockData) {
        return cache.get(blockData);
    }

    /** Resolve each distinct state and preserve the caller's iteration order. */
    public CompletableFuture<Map<String, BlockVisualShapeResolver.Resolution>> resolveAll(
            final Collection<String> blockDataStates) {
        final Map<String, CompletableFuture<BlockVisualShapeResolver.Resolution>> futures =
            new LinkedHashMap<>();
        for (final String blockData : blockDataStates) {
            futures.computeIfAbsent(blockData, this::resolve);
        }
        return CompletableFuture.allOf(
            futures.values().toArray(CompletableFuture[]::new))
            .thenApply(ignored -> {
                final Map<String, BlockVisualShapeResolver.Resolution> resolved =
                    new LinkedHashMap<>();
                futures.forEach((state, future) -> resolved.put(state, future.join()));
                return Map.copyOf(resolved);
            });
    }

    /** Resolve and run a potentially expensive planner on the cache executor. */
    public <T> CompletableFuture<T> resolveAllAndApply(
            final Collection<String> blockDataStates,
            final Function<Map<String, BlockVisualShapeResolver.Resolution>, T> planner) {
        return resolveAll(blockDataStates).thenApplyAsync(planner, executor);
    }

    public void clear() {
        cache.synchronous().invalidateAll();
    }
}
