package dev.twme.sculpt.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import dev.twme.sculpt.skin.HeadsRegistry;
import dev.twme.sculpt.skin.bake.BlockBaker;

/**
 * 實際查 HeadsRegistry 的 HeadResolver 實作。
 * 未 bake 時回傳帶有明確 placeholder 狀態的黃色羊毛內容。
 */
final class RegistryHeadResolver implements HeadResolver {

    /**
     * Parse a UUID string that may lack dashes (e.g. head catalogs
     * 32-char hex without dashes). Falls back to UUID.randomUUID()
     * on failure. DO NOT REMOVE this helper — {@link UUID#fromString}
     * REQUIRES dashes in the standard 8-4-4-4-12 format.
     */
    private static java.util.UUID parseUuid(final String str) {
        if (str == null || str.isEmpty()) return java.util.UUID.randomUUID();
        if (str.contains("-")) return java.util.UUID.fromString(str);
        // Insert dashes: 8-4-4-4-12
        return java.util.UUID.fromString(str.substring(0, 8) + "-"
            + str.substring(8, 12) + "-"
            + str.substring(12, 16) + "-"
            + str.substring(16, 20) + "-"
            + str.substring(20));
    }

    /**
     * CUBE_CENTER_PRE = (0, -0.25, 0)：player head ItemDisplay 模型的
     * 視覺中心相對於 entity origin 的偏移。
     */
    private static final Vector3f CUBE_CENTER_PRE = new Vector3f(0, -0.25f, 0);

    private static final Quaternionf CANONICAL_ROTATION = new Quaternionf()
        .rotateY((float) Math.toRadians(180));

    /**
     * Pre-computed {@code CANONICAL_ROTATION.transform(CUBE_CENTER_PRE)}.
     * Used in {@link #buildSkullTransform} to avoid re-computing the same
     * quaternion·vector product (2 allocations) per call — it's the same
     * for every head at every scale; only the per-scale `mul` varies.
     */
    private static final Vector3f CANONICAL_CENTER_OFFSET;
    static {
        final Vector3f tmp = new Quaternionf(CANONICAL_ROTATION)
            .transform(new Vector3f(CUBE_CENTER_PRE));
        CANONICAL_CENTER_OFFSET = new Vector3f(tmp.x, tmp.y, tmp.z);
    }

    /** Reusable identity quaternion for rotation-equality checks. */
    private static final Quaternionf ZERO_ROTATION = new Quaternionf();

    private final Map<Integer, HeadsRegistry> registriesByGridN;
    private final Map<Integer, BlockBaker> bakersByGridN;
    private final java.util.function.BiConsumer<BlockBaker.Batch, SculptBlock> onBakeTriggered;
    private final java.util.function.BiConsumer<SculptBlock, Runnable> onRegistryDataReady;
    /** Cold cell loads are coalesced into one re-render per SculptBlock batch. */
    private final Map<SculptBlock, PendingRegistryRefresh> pendingRegistryRefresh =
            new ConcurrentHashMap<>();
    /** Variant refreshes are retained until their region callback applies. */
    private final Map<SculptBlock, CompletableFuture<Boolean>> pendingVariantRefresh =
            new ConcurrentHashMap<>();

    private record RegistryRequest(
            HeadsRegistry registry, BakeKey bakeKey, ChunkCoord coord) {}

    private static final class PendingRegistryRefresh {
        private final Map<RegistryRequest, CompletableFuture<Boolean>> requests =
                new java.util.HashMap<>();
        private boolean refreshRequired;
    }

    /** 變體旋轉快取（輸入 BlockData 字串 + gridN → VariantResolution）。 */
    private final com.github.benmanes.caffeine.cache.Cache<String, VariantResolution> variantCache =
        com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(4096)
            .build();

    RegistryHeadResolver(Map<Integer, HeadsRegistry> registriesByGridN,
                         Map<Integer, BlockBaker> bakersByGridN,
                         java.util.function.BiConsumer<BlockBaker.Batch, SculptBlock> onBakeTriggered) {
        this(registriesByGridN, bakersByGridN, onBakeTriggered, null);
    }

    RegistryHeadResolver(Map<Integer, HeadsRegistry> registriesByGridN,
                         Map<Integer, BlockBaker> bakersByGridN,
                         java.util.function.BiConsumer<BlockBaker.Batch, SculptBlock> onBakeTriggered,
                         java.util.function.BiConsumer<SculptBlock, Runnable> onRegistryDataReady) {
        this.registriesByGridN = registriesByGridN;
        this.bakersByGridN = bakersByGridN;
        this.onBakeTriggered = onBakeTriggered;
        this.onRegistryDataReady = onRegistryDataReady;
    }

    /** Pick the registry matching this node's grid size. */
    private HeadsRegistry registryFor(OctreeNode node, SculptBlock block) {
        int gridN = 1 << node.depth();
        HeadsRegistry reg = registriesByGridN.get(gridN);
        if (reg != null) return reg;
        // Fall back to the smallest grid available
        for (int g = 2; g <= 16; g *= 2) {
            reg = registriesByGridN.get(g);
            if (reg != null) return reg;
        }
        return registriesByGridN.values().iterator().next();
    }

    @Override
    public ChunkHead headFor(OctreeNode node, SculptBlock block) {
        // A texture copied from a held player-head item is already complete.
        // It neither belongs to a baked block atlas nor requires MineSkin.
        final PlayerHeadTexture heldTexture = node.playerHeadTexture();
        if (heldTexture != null) {
            return buildSkullHead(
                heldTexture.value(), heldTexture.signature(), node, block);
        }

        BlockData leafData = node.blockData();
        if (leafData == null) leafData = block.originalBlockData;
        BlockKey blockKey = BlockKey.from(leafData);
        int gridN = 1 << node.depth();
        HeadsRegistry reg = registryFor(node, block);

        // 決定紋理查詢用的 cell 座標，優先順序：
        //   1. textureCoord — 每個節點獨立的紋理座標覆寫（八元樹層級）
        //      （仍會經過 rotateCoord，與物理座標行為一致）
        //   2. storedCoords — 藍圖貼上時儲存的規範化座標（跳過 rotateCoord）
        //   3. 物理座標 + rotateCoord（預設行為）
        final ChunkCoord coord = HeadResolver.textureCoordFor(node, block);

        // Use tinted BakeKey if the block carries a resolved biome tint.
        BakeKey bakeKey = block.tintArgb != 0
                ? new BakeKey(blockKey, block.tintArgb)
                : BakeKey.untinted(blockKey);
        if (!block.reRendering && (block.matchedVariantKey == null
                || block.matchedVariantKey.isEmpty())
                && reg.hasKnownBlock(blockKey)
                && reg.variantsLoadedIfPresent(blockKey) == null) {
            requestVariantRefresh(reg, blockKey, block);
        }
        // Region/edit threads must never perform a cold SQLite/SBH read. A
        // miss is resolved by the async registry prefetch below; the caller
        // decides whether to expose the placeholder or keep another renderer.
        java.util.Optional<HeadsRegistry.Entry> entry = reg.getIfLoaded(bakeKey, coord);
        SkinData skin = null;
        if (entry != null && entry.isPresent()) {
            HeadsRegistry.Entry e = entry.get();
            java.util.UUID uuid;
            try {
                uuid = parseUuid(e.mineskinUuid());
            } catch (final IllegalArgumentException ex) {
                uuid = java.util.UUID.randomUUID();
            }
            skin = new SkinData(e.textureValue(), e.textureSignature(), uuid);
        }
        // Only build a PLAYER_HEAD when the skin payload carries actual
        // texture data. Catalog entries generated without MineSkin uploads
        // (e.g. bake.py --local) have empty value/signature strings;
        // creating a PLAYER_HEAD with an empty ProfileProperty causes
        // the client to fail RSA signature verification and fall back
        // to the default Steve/Alex model.
        if (skin != null && !skin.value().isEmpty() && !skin.signature().isEmpty()) {
            return buildSkullHead(skin.value(), skin.signature(), node, block);
        }

        // 尚未 bake → 觸發目前 cell 的 sibling batch，並逐級補齊缺少的
        // parent-resolution batch。reRendering 時不觸發，避免 callback 循環。
        if (!block.reRendering) {
            requestMissingHierarchy(bakeKey, gridN, coord, block);
        }
        return buildPlaceholderHead(node, block);
    }

    void requestMissingHierarchy(
            BakeKey bakeKey, int gridN, ChunkCoord coord, SculptBlock block) {
        int levelGrid = gridN;
        ChunkCoord levelCoord = coord;
        while (levelGrid >= 2) {
            HeadsRegistry registry = registriesByGridN.get(levelGrid);
            BlockBaker baker = bakersByGridN.get(levelGrid);
            if (registry != null) {
                java.util.Optional<HeadsRegistry.Entry> existing =
                        registry.getIfLoaded(bakeKey, levelCoord);
                if (existing.isPresent() && isRenderable(existing.get())) {
                    // Hot, complete catalog entry.
                } else if (registry.hasKnownBlock(bakeKey)
                        && (!registry.hasLoaded(bakeKey)
                            || registry.containsLoadedChunk(bakeKey, levelCoord))) {
                    // Cold index or cold payload. The completion callback
                    // verifies this exact coordinate before deciding whether
                    // to re-render or fall back to runtime baking.
                    requestRegistryPrefetch(
                            registry, bakeKey, levelCoord, baker, block);
                } else if (baker != null) {
                    triggerBake(baker, bakeKey, levelCoord, block);
                }
            }

            levelGrid /= 2;
            if (levelGrid >= 2) {
                levelCoord = new ChunkCoord(
                        levelCoord.x() / 2,
                        levelCoord.y() / 2,
                        levelCoord.z() / 2);
            }
        }
    }

    private void requestRegistryPrefetch(
            HeadsRegistry registry, BakeKey bakeKey, ChunkCoord coord,
            BlockBaker baker, SculptBlock block) {
        if (block == null || onRegistryDataReady == null) {
            final CompletableFuture<Boolean> future = registry.prefetch(bakeKey, coord);
            future.whenComplete((loaded, failure) -> completeRegistryPrefetch(
                    registry, bakeKey, coord, baker, block, failure));
            return;
        }

        final RegistryRequest request = new RegistryRequest(registry, bakeKey, coord);
        final AtomicReference<CompletableFuture<Boolean>> futureRef = new AtomicReference<>();
        final AtomicBoolean added = new AtomicBoolean();
        pendingRegistryRefresh.compute(block, (ignored, pending) -> {
            if (pending == null) pending = new PendingRegistryRefresh();
            CompletableFuture<Boolean> future = pending.requests.get(request);
            if (future == null) {
                future = registry.prefetch(bakeKey, coord);
                pending.requests.put(request, future);
                added.set(true);
            }
            futureRef.set(future);
            return pending;
        });
        if (!added.get()) return;

        final CompletableFuture<Boolean> future = futureRef.get();
        future.whenComplete((loaded, failure) -> {
            boolean renderable = false;
            try {
                renderable = completeRegistryPrefetch(
                        registry, bakeKey, coord, baker, block, failure);
            } finally {
                finishRegistryRequest(block, request, future, renderable);
            }
        });
    }

    private void finishRegistryRequest(
            SculptBlock block, RegistryRequest request,
            CompletableFuture<Boolean> future, boolean renderable) {
        final AtomicBoolean shouldRefresh = new AtomicBoolean();
        pendingRegistryRefresh.computeIfPresent(block, (ignored, pending) -> {
            if (!pending.requests.remove(request, future)) return pending;
            pending.refreshRequired |= renderable;
            if (!pending.requests.isEmpty()) return pending;
            shouldRefresh.set(pending.refreshRequired);
            return null;
        });
        if (shouldRefresh.get()) scheduleRegistryRefresh(block);
    }

    private void scheduleRegistryRefresh(SculptBlock block) {
        try {
            onRegistryDataReady.accept(block, () -> { });
        } catch (RuntimeException ignored) {
            // The owning plugin may be disabling or the block may have been
            // removed between the I/O completion and callback.
        }
    }

    private boolean completeRegistryPrefetch(
            HeadsRegistry registry, BakeKey bakeKey, ChunkCoord coord,
            BlockBaker baker, SculptBlock block, Throwable failure) {
        if (failure != null) {
            if (baker != null) triggerBake(baker, bakeKey, coord, block);
            return false;
        }
        final java.util.Optional<HeadsRegistry.Entry> entry =
                registry.getIfLoaded(bakeKey, coord);
        if (entry.isEmpty() || !isRenderable(entry.get())) {
            if (baker != null) triggerBake(baker, bakeKey, coord, block);
            return false;
        }
        return block != null && onRegistryDataReady != null;
    }

    private void triggerBake(
            BlockBaker baker, BakeKey bakeKey, ChunkCoord coord, SculptBlock block) {
        final BlockBaker.Batch batch = baker.batchFor(bakeKey, coord);
        // Track before starting the async work so a cache-only bake cannot
        // complete before its waiting block is registered.
        if (onBakeTriggered != null) onBakeTriggered.accept(batch, block);
        baker.bake(batch);
    }

    private void requestVariantRefresh(
            HeadsRegistry registry, BlockKey blockKey, SculptBlock block) {
        final Map<String, dev.twme.sculpt.assets.model.ModelResolver.VariantRotation> residentVariants =
                registry.variantsForIfLoaded(blockKey);
        final CompletableFuture<Boolean> future = residentVariants.isEmpty()
                ? registry.prefetchIndex(blockKey)
                : CompletableFuture.completedFuture(true);
        if (onRegistryDataReady == null) return;
        if (pendingVariantRefresh.putIfAbsent(block, future) != null) return;

        future.whenComplete((loaded, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(loaded)) {
                pendingVariantRefresh.remove(block, future);
                return;
            }
            try {
                onRegistryDataReady.accept(block, () -> {
                    try {
                        // Resolve Bukkit BlockData only after the scheduler has
                        // returned us to the owning region thread.
                        final VariantResolution resolution =
                                resolveVariantImpl(block.originalBlockData, registry.gridN());
                        if (resolution.matchedVariant().isEmpty()) return;
                        if (block.matchedVariantKey == null
                                || block.matchedVariantKey.isEmpty()) {
                            block.updateVariant(
                                    resolution.matchedVariant(), resolution.rotation());
                        }
                    } finally {
                        pendingVariantRefresh.remove(block, future);
                    }
                });
            } catch (RuntimeException ignoredCallback) {
                pendingVariantRefresh.remove(block, future);
            }
        });
    }

    private static boolean isRenderable(HeadsRegistry.Entry entry) {
        return entry.textureValue() != null && !entry.textureValue().isEmpty()
                && entry.textureSignature() != null && !entry.textureSignature().isEmpty();
    }

    @Override
    public VariantResolution resolveVariant(BlockData data, int gridN) {
        // 快取查詢：同一 (blockData, gridN) 組合結果不變
        final String cacheKey = data.getAsString() + "|" + gridN;
        final VariantResolution cached = variantCache.getIfPresent(cacheKey);
        if (cached != null) {
            return new VariantResolution(
                new Quaternionf(cached.rotation()), cached.matchedVariant());
        }

        final VariantResolution resolved = resolveVariantImpl(data, gridN);
        // Do not cache an identity result produced while the variant index is
        // still cold: the async prefetch can provide a real orientation on the
        // next edit. Stable matched variants remain cached as before.
        if (!resolved.matchedVariant().isEmpty()
                || !variantIndexCold(data, gridN)) {
            variantCache.put(cacheKey, resolved);
        }
        // 回傳防禦性複製（Quaternionf 為 mutable type）
        return new VariantResolution(
            new Quaternionf(resolved.rotation()), resolved.matchedVariant());
    }

    private boolean variantIndexCold(BlockData data, int gridN) {
        HeadsRegistry reg = registriesByGridN.get(gridN);
        if (reg == null) {
            for (int g = 2; g <= 16; g *= 2) {
                reg = registriesByGridN.get(g);
                if (reg != null) break;
            }
        }
        return reg != null
                && reg.hasKnownBlock(BlockKey.from(data))
                && reg.variantsLoadedIfPresent(BlockKey.from(data)) == null;
    }

    private VariantResolution resolveVariantImpl(BlockData data, int gridN) {
        // Pick the registry that has this grid, or fall back to any available
        HeadsRegistry reg = registriesByGridN.get(gridN);
        if (reg == null) {
            for (int g = 2; g <= 16; g *= 2) {
                reg = registriesByGridN.get(g);
                if (reg != null) break;
            }
        }
        if (reg == null) return new VariantResolution(new Quaternionf(), "");

        String s = data.getAsString();
        // Strip block id prefix and square brackets
        String propsRaw = s.contains("[") ? s.substring(s.indexOf('[') + 1, s.indexOf(']')) : "";
        BlockKey blockKey = BlockKey.from(data);

        // Build variant key: sort props alphabetically for canonical form
        String variantKey = formatVariantKey(propsRaw);

        // Variant maps are read without blocking. If this block is known but
        // cold, ask the registry to warm it and let the next edit use the
        // resolved orientation.
        final Map<String, dev.twme.sculpt.assets.model.ModelResolver.VariantRotation> variants =
                reg.variantsForIfLoaded(blockKey);
        if (variants.isEmpty()) {
            if (reg.hasKnownBlock(blockKey)
                    && reg.variantsLoadedIfPresent(blockKey) == null) {
                reg.prefetchIndex(blockKey);
            }
            return new VariantResolution(new Quaternionf(), "");
        }

        // Try the registry's baked variant rotations first
        Quaternionf rot = reg.rotationForIfLoaded(blockKey, variantKey);
        if (variants.containsKey(variantKey) || variantKey.isEmpty()) {
            return new VariantResolution(rot, variantKey);
        }

        // Relaxed matching: drop nuisance props one by one
        String[] nuisance = {"waterlogged", "powered", "lit", "distance",
            "persistent", "stage", "half", "open"};

        String cur = propsRaw;
        while (!cur.isEmpty()) {
            String[] parts = cur.split(",");
            String dropped = null;
            for (int i = parts.length - 1; i >= 0; i--) {
                String p = parts[i].trim();
                String name = p.split("=")[0];
                for (String n : nuisance) {
                    if (n.equals(name)) {
                        dropped = p;
                        break;
                    }
                }
                if (dropped != null) break;
            }
            if (dropped == null) break; // nothing left to drop

            // Rebuild without the dropped property（使用陣列取代 ArrayList）
            String[] remaining = new String[parts.length];
            int ri = 0;
            for (String p : parts) {
                if (!p.trim().equals(dropped)) remaining[ri++] = p.trim();
            }
            cur = String.join(",", java.util.Arrays.copyOf(remaining, ri));
            String relaxedKey = cur.isEmpty() ? "normal" : cur;
            rot = reg.rotationForIfLoaded(blockKey, relaxedKey);
            if (variants.containsKey(relaxedKey)) {
                return new VariantResolution(rot, relaxedKey);
            }
        }

        // Fallback: try "normal" key
        rot = reg.rotationForIfLoaded(blockKey, "normal");
        if (variants.containsKey("normal")) {
            return new VariantResolution(rot, "normal");
        }

        return new VariantResolution(ZERO_ROTATION, "");
    }

    /**
     * Normalise a comma-separated blockstate property string into the
     * canonical sorted-by-name variant key used in baked blockstate JSON.
     * E.g. {@code "axis=z,facing=east"} → {@code "axis=z,facing=east"}
     * (already sorted alphabetically by vanilla convention, but we force
     * it for robustness).
     */
    private static String formatVariantKey(String props) {
        if (props.isEmpty()) return "";
        String[] parts = props.split(",");
        java.util.Arrays.sort(parts);
        return String.join(",", parts);
    }

    /**
     * 黃色羊毛佔位頭顱（保留舊算法 — 已知尺寸正確，不變動）。
     */
    private ChunkHead buildPlaceholderHead(OctreeNode node, SculptBlock block) {
        int gridN = 2 << node.depth();
        float scale = 2f / gridN;

        Transformation transform = buildPlaceholderTransform(node, block, scale);
        return new ChunkHead(
            new ItemStack(Material.YELLOW_WOOL), transform, true);
    }

    /**
     * 黃色羊毛佔位頭顱的 Transformation（保留原算法）。
     * <p>平面物品無 CUBE_CENTER_PRE 問題，使用最簡公式。
     */
    private Transformation buildPlaceholderTransform(OctreeNode node, SculptBlock block, float scale) {
        float tx = ((node.minX() + node.side() / 2f) / 16f) - 0.5f;
        float ty = ((node.minY() + node.side() / 2f) / 16f) - 0.5f;
        float tz = ((node.minZ() + node.side() / 2f) / 16f) - 0.5f;

        return new Transformation(
            new Vector3f(tx, ty, tz),
            new Quaternionf(block.blockRotation),
            new Vector3f(scale, scale, scale),
            new Quaternionf());
    }

    /**
     * 用正確 skin 建立玩家頭顱。
     * <p>scale 公式 {@code 2f / gridN} = {@code 1f / (1 << depth)}。
     * depth=1 → scale=1.0（grid=2），depth=2 → scale=0.5（grid=4）。
     */
    private ChunkHead buildSkullHead(
            String textureValue, String textureSignature,
            OctreeNode node, SculptBlock block) {
        int gridN = 1 << node.depth();
        float scale = 2f / gridN;

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        // NIL UUID prevents Paper from trying to fetch profile properties
        // from Mojang's session server (avoiding 429 rate-limit errors).
        PlayerProfile profile = Bukkit.createProfile(new UUID(0L, 0L), "Sculpt");
        profile.setProperty(textureSignature == null || textureSignature.isEmpty()
            ? new ProfileProperty("textures", textureValue)
            : new ProfileProperty("textures", textureValue, textureSignature));
        meta.setPlayerProfile(profile);
        head.setItemMeta(meta);

        Transformation transform = buildSkullTransform(node, block, scale);
        return new ChunkHead(head, transform);
    }

    /**
     * PLAYER_HEAD 專用的 Transformation。
     *
     * <p>公式依據 Tessera {@code BlockGeometry.translationFor}：
     * <pre>
     *   cellLocal = (cellCenter / 16) - 0.5        // 與黃色羊毛相同，範圍 [-0.5, +0.5]
     *   rotatedCell = blockRotation * cellLocal
     *   disp = blockRotation * (CANONICAL_ROTATION * CUBE_CENTER_PRE * scale)
     *   T = cellLocal - disp
     * </pre>
     * 與羊毛差異只在減去 {@code disp}（player-head 模型的視覺中心
     * 不在 entity 原點，需要等比補償）。
     */
    private Transformation buildSkullTransform(OctreeNode node, SculptBlock block, float scale) {
        // 與 buildPlaceholderTransform 同一套 cellLocal（羊毛公式）
        float cellX = ((node.minX() + node.side() / 2f) / 16f) - 0.5f;
        float cellY = ((node.minY() + node.side() / 2f) / 16f) - 0.5f;
        float cellZ = ((node.minZ() + node.side() / 2f) / 16f) - 0.5f;
        Vector3f cellLocal = new Vector3f(cellX, cellY, cellZ);

        // CANONICAL_CENTER_OFFSET * scale → blockRotation
        Vector3f disp = new Vector3f(CANONICAL_CENTER_OFFSET)
            .mul(scale, scale, scale);
        new Quaternionf(block.blockRotation).transform(disp);

        return new Transformation(
            new Vector3f(cellLocal.x - disp.x, cellLocal.y - disp.y, cellLocal.z - disp.z),
            new Quaternionf(block.blockRotation),
            new Vector3f(scale, scale, scale),
            new Quaternionf(CANONICAL_ROTATION));
    }

    /**
     * Rotate a chunk coordinate by the inverse of the block's orientation
     * quaternion, so texture tiles are distributed as if the lattice itself
     * were rotated rather than just each cell's model in isolation.
     * <p>
     * When a block is rotated (e.g. a log with {@code axis=z}), the physical
     * cell at chunk coordinates (gx, gy, gz) should display the baked tile
     * of the canonical cell that, after applying {@code rotation}, lands at
     * this physical position.  The mapping is therefore done via the
     * <em>inverse</em> rotation ({@code rotation⁻¹}).
     * <p>
     * Vanilla blockstate rotations are always multiples of 90°, so the
     * rounded result is exact.  Clamps to valid chunk-index range as a
     * safety net.
     *
     * @param coord    the physical chunk coordinate
     * @param rotation the block's orientation quaternion (maps canonical
     *                 → oriented)
     * @param gridN    the grid size for this node
     * @return the canonical chunk coordinate whose skin should be used
     */
    public static ChunkCoord rotateCoord(ChunkCoord coord, Quaternionf rotation, int gridN) {
        return HeadResolver.rotateCoord(coord, rotation, gridN);
    }
}
