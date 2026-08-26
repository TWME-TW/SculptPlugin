package dev.twme.sculpt.render.text;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.bukkit.Material;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.assets.model.BlockModel;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.ChunkSpec;
import dev.twme.sculpt.split.TextureSplitter;

/** Async, bounded cache of transparent-capable per-cell texture tiles. */
public final class TextDisplayTextureCache {

    private final TextDisplayMaterialSupport materialSupport;
    private final TextureSplitter splitter = new TextureSplitter();
    private final Executor executor;
    private final AsyncLoadingCache<GridKey, Optional<TextureSplitter.PreparedGrid>> grids;

    public TextDisplayTextureCache(
            final McAssetClient assets,
            final String minecraftVersion,
            final java.util.logging.Logger logger,
            final Executor executor) {
        this.materialSupport = new TextDisplayMaterialSupport(
            assets, minecraftVersion, logger, executor);
        this.executor = executor;
        this.grids = Caffeine.newBuilder()
            .maximumWeight(32_768)
            .weigher((GridKey key, Optional<TextureSplitter.PreparedGrid> ignored) ->
                key.gridN * key.gridN)
            .buildAsync((key, ignoredExecutor) -> materialSupport
                .resolve(key.bakeKey.block())
                .thenApplyAsync(model -> loadGrid(key, model), this.executor));
    }

    public CompletableFuture<Optional<ChunkSpec>> get(
            final BakeKey bakeKey,
            final int gridN,
            final ChunkCoord coord) {
        final TextureKey key = new TextureKey(bakeKey, gridN, coord);
        return grids.get(key.gridKey())
            .thenApply(prepared -> prepared.map(grid -> grid.cell(coord)));
    }

    public void clear() {
        grids.synchronous().invalidateAll();
        materialSupport.clear();
    }

    public TextDisplayMaterialSupport.Status materialSupport(
            final Material material,
            final boolean load) {
        if (material == null || !material.isBlock() || material.isAir()) {
            return TextDisplayMaterialSupport.Status.UNSUPPORTED;
        }
        return materialSupport.status(
            BlockKey.of(material.getKey().toString()), load);
    }

    public TextDisplayMaterialSupport.ModelStatus modelStatus(
            final Material material,
            final boolean load) {
        if (material == null || !material.isBlock() || material.isAir()) {
            return TextDisplayMaterialSupport.ModelStatus.UNSUPPORTED;
        }
        return materialSupport.modelStatus(
            BlockKey.of(material.getKey().toString()), load);
    }

    public CompletableFuture<TextDisplayMaterialSupport.ModelStatus> resolveStatus(
            final Material material) {
        if (material == null || !material.isBlock() || material.isAir()) {
            return CompletableFuture.completedFuture(
                TextDisplayMaterialSupport.ModelStatus.UNSUPPORTED);
        }
        return materialSupport.resolveStatus(
            BlockKey.of(material.getKey().toString()));
    }

    private Optional<TextureSplitter.PreparedGrid> loadGrid(
            final GridKey key,
            final Optional<BlockModel> resolved) {
        if (resolved.isEmpty()) return Optional.empty();
        BlockModel model = resolved.get();
        if (model.tinted() && key.bakeKey.tintArgb() != 0) {
            model = model.withTint(key.bakeKey.tintArgb());
        }
        return Optional.of(splitter.prepare(model, key.gridN));
    }

    private record TextureKey(BakeKey bakeKey, int gridN, ChunkCoord coord) {
        private TextureKey {
            if (bakeKey == null || coord == null) {
                throw new IllegalArgumentException("texture cache key must be complete");
            }
            if (gridN < 2 || gridN > 16 || Integer.bitCount(gridN) != 1) {
                throw new IllegalArgumentException("invalid gridN: " + gridN);
            }
        }

        private GridKey gridKey() {
            return new GridKey(bakeKey, gridN);
        }
    }

    private record GridKey(BakeKey bakeKey, int gridN) {
        private GridKey {
            if (bakeKey == null || gridN < 2 || gridN > 16
                    || Integer.bitCount(gridN) != 1) {
                throw new IllegalArgumentException("invalid texture grid key");
            }
        }
    }
}
