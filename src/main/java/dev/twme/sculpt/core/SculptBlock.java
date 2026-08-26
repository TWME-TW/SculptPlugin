package dev.twme.sculpt.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.joml.Quaternionf;

import dev.twme.sculpt.transport.DisplayHandle;
import dev.twme.sculpt.transport.TransportSession;
import dev.twme.sculpt.render.TextBlockRenderHandle;
import dev.twme.sculpt.render.TextBlockRenderer;
import dev.twme.sculpt.render.TextLightingRefreshResult;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.InteractionSpawner;
import dev.twme.sculpt.util.ShulkerSpawner;

/**
 * 新版 SculptBlock。以 OctreeNode 八元樹取代舊的 List<ChunkRef>。
 *
 * <p>狀態機：COMPLETE（過渡狀態，原方塊已回復）↔ SCULPTED
 *（依填充策略使用 BARRIER/AIR，加上 rootEntity 與顯示實體）。
 * 當整塊完全回復原方塊或被清空時，會呼叫清除回呼，由外層自動從 activeBlocks 移除。
 *
 * <p>操作：subdivide/coarsen/remove/restore 均在 SculptBlock 層級，
 * OctreeNode 僅為純資料結構。
 */
public final class SculptBlock {

    public enum State { COMPLETE, SCULPTED }

    // ====================== 識別 ======================

    public final World world;
    public final Location pos;
    /** Base material used when serializing leaf overrides and completing the block. */
    public volatile BlockData originalBlockData;
    /** Resolved blockstate variant; populated asynchronously for cold catalogs. */
    public volatile String matchedVariantKey;
    /** Canonical-to-world rotation for {@link #matchedVariantKey}. */
    public final Quaternionf blockRotation;

    // ====================== 中心座標快取 ======================

    /** 方塊中心座標（避免每次 centerLoc() 執行 pos.clone().add()）。 */
    public final double centerX, centerY, centerZ;

    // ====================== 基底實體 ======================

    public DisplayHandle rootEntity;           // null when COMPLETE

    // ====================== 樹結構 ======================

    public final int maxDepth;                // 固定 4 (grid=16)
    public final OctreeNode root;             // depth=0

    // ====================== 執行期狀態 ======================

    public State state;
    public final TransportSession session;
    public final HeadResolver headResolver;

    /**
     * 藍圖貼上時儲存的 cell 座標映射：路徑字串 → 規範化 ChunkCoord [x, y, z]。
     * 非 null 代表此 SculptBlock 是由藍圖貼上建立的，headFor 應優先使用此座標查 skin。
     */
    public Map<String, int[]> storedCoords = null;

    public boolean despawned = false;
    /** Guard: set true during {@link #reRender()} so {@code headFor} skips triggering new bakes. */
    public volatile boolean reRendering = false;
    /** Nested batch depth for deferring collision reconciliation and empty cleanup. */
    private int batchOperationDepth = 0;
    /** Optional callback invoked when this SculptBlock is fully cleared and should be unregistered. */
    private Runnable onCleared = () -> {};

    // ====================== 填充與顯示策略 ======================

    private FillMode fillMode = FillMode.BARRIER;
    private SculptDisplayMode displayMode = SculptDisplayMode.HEAD;
    private TextBlockRenderer textBlockRenderer;
    private TextBlockRenderHandle textRenderHandle;

    /** Canonical occupancy-only octree; independent from display material topology. */
    private CollisionOctree collisionTree;

    /** Runtime Shulkers keyed by canonical collision-node path. */
    private final Map<String, Shulker> collisionShulkers = new LinkedHashMap<>();

    /** Interaction click proxy used by every non-empty AIR-backed strategy. */
    private Interaction clickProxy = null;

    public Interaction clickProxy() { return clickProxy; }
    public void attachClickProxy(final Interaction interaction) { this.clickProxy = interaction; }
    public void detachClickProxy() { this.clickProxy = null; }

    public CollisionOctree collisionTree() { return collisionTree; }

    public int collisionEntityCount() { return collisionShulkers.size(); }

    public FillMode fillMode() { return fillMode; }

    public SculptDisplayMode displayMode() { return displayMode; }

    public TextBlockRenderHandle textRenderHandle() { return textRenderHandle; }

    /** Configure strategies before first spawn, or migrate an active block. */
    public void configureStrategies(
            final FillMode newFillMode,
            final SculptDisplayMode newDisplayMode,
            final TextBlockRenderer renderer) {
        this.textBlockRenderer = renderer;
        setDisplayMode(newDisplayMode);
        setFillMode(newFillMode);
    }

    public boolean setFillMode(final FillMode mode) {
        final FillMode resolved = mode == null ? FillMode.BARRIER : mode;
        if (fillMode == resolved) return false;
        fillMode = resolved;
        if (state == State.SCULPTED && !despawned) reconcileFillState();
        markPDCDirty();
        return true;
    }

    public boolean setDisplayMode(final SculptDisplayMode mode) {
        final SculptDisplayMode resolved = mode == null
            ? SculptDisplayMode.HEAD : mode;
        if (displayMode == resolved) return false;
        displayMode = resolved;
        if (state == State.SCULPTED && !despawned) rebuildVisualRepresentation();
        markPDCDirty();
        return true;
    }

    /** Full occupancy uses one real barrier block and no collision entities. */
    public boolean usesFullBlockCollision() {
        return fillMode == FillMode.SHULKER
            && state == State.SCULPTED && !despawned
            && collisionTree.isFullyOccupied();
    }

    /** Partial non-empty occupancy uses Shulkers and an Interaction proxy. */
    public boolean usesEntityCollision() {
        return fillMode == FillMode.SHULKER
            && state == State.SCULPTED && !despawned
            && !collisionTree.isEmpty() && !collisionTree.isFullyOccupied();
    }

    /** AIR-backed strategies use one Interaction entity for click detection. */
    public boolean usesEntityInteraction() {
        if (state != State.SCULPTED || despawned || collisionTree.isEmpty()) {
            return false;
        }
        return fillMode == FillMode.NONE
            || (fillMode == FillMode.SHULKER && !collisionTree.isFullyOccupied());
    }

    public Shulker collisionShulker(final String path) {
        return collisionShulkers.get(path);
    }

    /**
     * Attach a loaded or newly spawned Shulker to an exact canonical
     * collision leaf. Returns false for stale display-tree paths or duplicates.
     */
    public boolean attachCollisionShulker(final String path, final Shulker shulker) {
        if (shulker == null) return false;
        if (collisionTree.isFullyOccupied()) return false;
        final CollisionOctree.Node node = collisionTree.nodeAtPath(path);
        if (node == null || !node.isOccupied()) return false;

        final Shulker existing = collisionShulkers.get(path);
        if (existing == shulker) return true;
        if (existing != null && existing.isValid()) return false;
        if (existing != null) ShulkerSpawner.remove(existing);
        collisionShulkers.put(path, shulker);
        return true;
    }

    /** Rebuild only the material-independent topology from display occupancy. */
    public void rebuildCollisionTopology() {
        this.collisionTree = CollisionOctree.from(root);
    }

    /**
     * Rebuild and apply the adaptive collision representation. Full occupancy
     * uses one BARRIER block; partial occupancy uses AIR, one click proxy, and
     * one Shulker for every canonical occupied leaf.
     */
    public void reconcileCollisionState() {
        reconcileFillState();
    }

    /** Apply the selected backing-block/collision strategy. */
    public void reconcileFillState() {
        rebuildCollisionTopology();

        final var iterator = collisionShulkers.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, Shulker> entry = iterator.next();
            final CollisionOctree.Node node = collisionTree.nodeAtPath(entry.getKey());
            final Shulker shulker = entry.getValue();
            if (node == null || !node.isOccupied() || shulker == null || !shulker.isValid()) {
                ShulkerSpawner.remove(shulker);
                iterator.remove();
            }
        }

        if (state != State.SCULPTED || despawned) {
            cleanupShulkerEntities();
            return;
        }

        if (fillMode == FillMode.BARRIER) {
            cleanupShulkerEntities();
            if (pos.getBlock().getType() != Material.BARRIER) {
                pos.getBlock().setType(Material.BARRIER, false);
            }
            return;
        }

        if (fillMode == FillMode.NONE) {
            clearCollisionEntities();
            if (pos.getBlock().getType() != Material.AIR) {
                pos.getBlock().setType(Material.AIR, false);
            }
            if (collisionTree.isEmpty()) removeClickProxy();
            else ensureClickProxy();
            return;
        }

        if (collisionTree.isFullyOccupied()) {
            clearCollisionEntities();
            removeClickProxy();
            if (pos.getBlock().getType() != Material.BARRIER) {
                pos.getBlock().setType(Material.BARRIER, false);
            }
            return;
        }

        if (pos.getBlock().getType() != Material.AIR) {
            pos.getBlock().setType(Material.AIR, false);
        }

        if (collisionTree.isEmpty()) {
            clearCollisionEntities();
            removeClickProxy();
            return;
        }

        ensureClickProxy();

        for (final CollisionOctree.Node leaf : collisionTree.occupiedLeaves()) {
            if (!collisionShulkers.containsKey(leaf.path())) {
                ShulkerSpawner.spawn(this, leaf);
            }
        }
    }

    /** Remove every collision Shulker and its seat. */
    public void clearCollisionEntities() {
        for (final Shulker shulker : collisionShulkers.values()) {
            ShulkerSpawner.remove(shulker);
        }
        collisionShulkers.clear();
    }

    private void ensureClickProxy() {
        if (clickProxy != null && clickProxy.isValid()) {
            InteractionSpawner.align(clickProxy, centerLoc());
            return;
        }
        removeClickProxy();
        clickProxy = InteractionSpawner.spawn(centerLoc());
    }

    private void removeClickProxy() {
        InteractionSpawner.remove(clickProxy);
        clickProxy = null;
    }

    // ====================== 混合材質（SculptMode） ======================

    /**
     * 是否為混合材質 SculptBlock。
     * non-removed 葉子之間 blockData 不一致即為 true。
     * 全部一致地變成另一種材質（即使與 originalBlockData 不同）= false。
     */
    private boolean isMixed = false;

    /** 回傳此 SculptBlock 是否包含混合材質。 */
    public boolean isMixed() {
        return isMixed;
    }

    /** 設定混合材質狀態（由 reconstruction 使用）。 */
    public void setMixed(final boolean mixed) {
        this.isMixed = mixed;
    }

    /**
     * 遍歷所有 non-removed 葉子，若 blockData 全部一致則回傳 false，
     * 否則回傳 true（即存在異質材質）。
     */
    public boolean recomputeMixedState() {
        BlockData reference = null;
        PlayerHeadTexture referenceTexture = null;
        boolean referenceSet = false;
        for (final OctreeNode leaf : root.collectLeaves()) {
            if (leaf.isRemoved()) continue;
            BlockData bd = leaf.blockData();
            if (bd == null) bd = originalBlockData;
            if (!referenceSet) {
                reference = bd;
                referenceTexture = leaf.playerHeadTexture();
                referenceSet = true;
                continue;
            }
            if (!bd.equals(reference)
                    || !Objects.equals(
                        leaf.playerHeadTexture(), referenceTexture)) return true;
        }
        return false;
    }

    // ====================== 著色 ======================

    /** Resolved biome tint ARGB (0 = untinted) for the current base material. */
    public volatile int tintArgb;

    // ====================== 建構 ======================

    public SculptBlock(World world, Location pos, BlockData originalBlockData,
                       String matchedVariantKey, Quaternionf blockRotation,
                       TransportSession session, HeadResolver headResolver) {
        this(world, pos, originalBlockData, matchedVariantKey, blockRotation,
             session, headResolver, resolveTint(pos, originalBlockData));
    }

    /** Full constructor with explicit tint (used by deserialization from PDC). */
    public SculptBlock(World world, Location pos, BlockData originalBlockData,
                       String matchedVariantKey, Quaternionf blockRotation,
                       TransportSession session, HeadResolver headResolver,
                       int tintArgb) {
        this.world = world;
        this.pos = pos.toBlockLocation();
        this.originalBlockData = originalBlockData.clone();
        this.matchedVariantKey = matchedVariantKey;
        this.blockRotation = new Quaternionf(blockRotation);
        this.session = session;
        this.headResolver = headResolver;
        this.maxDepth = 4;  // grid=16
        this.root = new OctreeNode();
        this.collisionTree = CollisionOctree.from(root);
        this.state = State.COMPLETE;
        this.rootEntity = null;
        this.tintArgb = tintArgb;
        this.centerX = this.pos.getBlockX() + 0.5;
        this.centerY = this.pos.getBlockY() + 0.5;
        this.centerZ = this.pos.getBlockZ() + 0.5;
    }

    /** Resolve the position tint used by both visual backends. */
    public int tintFor(final BlockData data) {
        if (data == null) return 0;
        if (data.getMaterial() == originalBlockData.getMaterial()) return tintArgb;
        return resolveTint(pos, data);
    }

    public void setOnCleared(Runnable onCleared) {
        this.onCleared = (onCleared != null) ? onCleared : () -> {};
    }

    /**
     * Apply a variant resolved by the background heads registry. The quaternion
     * is updated in place because render and coordinate helpers retain it.
     * Callers must invoke this on the owning region thread.
     */
    public void updateVariant(final String variantKey, final Quaternionf rotation) {
        if (despawned) return;
        this.matchedVariantKey = variantKey == null ? "" : variantKey;
        this.blockRotation.set(rotation == null ? new Quaternionf() : rotation);
        markPDCDirty();
    }

    /**
     * Replace every cell material while preserving the SculptBlock's occupied
     * shape. Removed cells are updated too, so a later restore cannot bring
     * back the previous material. Atomic held-head cells become ordinary,
     * subdividable block cells.
     *
     * <p>A fully occupied result has no reason to remain represented by
     * display entities, so it is completed directly as one vanilla block.
     * Missing textures for partial blocks are requested during the refresh.
     * Callers must invoke this on the block's owning region thread.
     *
     * @param replacement replacement block data
     * @param variant resolved orientation for the replacement block data
     * @return whether the block changed
     */
    public boolean replaceAllMaterials(
            final BlockData replacement,
            final VariantResolution variant) {
        Objects.requireNonNull(replacement, "replacement");
        if (despawned || state != State.SCULPTED) return false;

        final BlockData replacementCopy = replacement.clone();
        final VariantResolution resolved = variant == null
            ? new VariantResolution(new Quaternionf(), "") : variant;
        final int replacementTint = resolveTint(pos, replacementCopy);

        rebuildCollisionTopology();
        if (collisionTree.isFullyOccupied()) {
            applyBaseMaterial(replacementCopy, resolved, replacementTint);
            despawn();
            pos.getBlock().setBlockData(replacementCopy, false);
            return true;
        }

        if (!needsMaterialReplacement(root, replacementCopy)
                && originalBlockData.equals(replacementCopy)
                && storedCoords == null
                && tintArgb == replacementTint
                && Objects.equals(matchedVariantKey, resolved.matchedVariant())
                && blockRotation.equals(resolved.rotation())) {
            return false;
        }

        applyBaseMaterial(replacementCopy, resolved, replacementTint);
        replaceNodeMaterials(root, replacementCopy);
        this.isMixed = false;
        invalidateAutoHeadDisplays();
        refreshLeafDisplays(false);
        syncPDC();
        return true;
    }

    private void applyBaseMaterial(
            final BlockData replacement,
            final VariantResolution variant,
            final int replacementTint) {
        this.originalBlockData = replacement.clone();
        this.matchedVariantKey = variant.matchedVariant() == null
            ? "" : variant.matchedVariant();
        this.blockRotation.set(variant.rotation() == null
            ? new Quaternionf() : variant.rotation());
        this.tintArgb = replacementTint;
        this.storedCoords = null;
    }

    private static boolean needsMaterialReplacement(
            final OctreeNode node,
            final BlockData replacement) {
        if (node.isLeaf()) {
            return !replacement.equals(node.blockData())
                || node.textureCoord() != null
                || node.playerHeadTexture() != null;
        }
        for (final OctreeNode child : node.children()) {
            if (needsMaterialReplacement(child, replacement)) return true;
        }
        return false;
    }

    private static void replaceNodeMaterials(
            final OctreeNode node,
            final BlockData replacement) {
        node.setBlockData(replacement.clone());
        node.setTextureCoord(null);
        node.setPlayerHeadTexture(null);
        if (node.isLeaf()) return;
        for (final OctreeNode child : node.children()) {
            replaceNodeMaterials(child, replacement);
        }
    }

    /** A material replacement invalidates every previously resolved AUTO head. */
    private void invalidateAutoHeadDisplays() {
        if (displayMode != SculptDisplayMode.AUTO) return;
        for (final OctreeNode leaf : root.collectLeaves()) {
            if (!leaf.isRemoved()) destroyLeafEntity(leaf);
        }
    }

    /**
     * Read the biome tint at this SculptBlock's position for its original
     * material. The backing block may already be AIR for an edge placement.
     * Returns 0 (untinted) for non-tinted blocks or when colormaps aren't
     * loaded.
     */
    private static int resolveTint(Location blockPos, BlockData originalData) {
        String name = originalData.getMaterial().getKey().getKey();
        // Only solid-texture tinted blocks whose base faces pass the
        // transparency check in ModelResolver. Leaves are excluded because
        // their semi-transparent base renders as holes in ItemDisplay;
        // grass_block side faces are tinted via overlay which becomes
        // opaque after compositing with the dirt-side base.
        if (!"grass_block".equals(name) && !name.endsWith("_leaves")) return 0;
        return dev.twme.sculpt.nms.BlockTintReader.readAt(
            blockPos.getBlock(), originalData.getMaterial());
    }

    // ====================== 初始化 ======================

    /**
     * 建立 8 個 depth=1 的葉子，每片的 blockData = originalBlockData。
     */
    public void initLeaves() {
        initLeavesDataOnly();
    }

    /**
     * Initialize the occupied volume represented by the source block. Regular
     * blocks and double slabs start full; bottom and top slabs occupy only the
     * matching half of the grid. The absent half remains restorable, so players
     * can still extend a sculpt beyond the source slab after conversion.
     */
    public void initFromOriginalBlockShape() {
        initializeOriginalBlockShape(root, originalBlockData);
        rebuildCollisionTopology();
    }

    static void initializeOriginalBlockShape(
            final OctreeNode targetRoot,
            final BlockData sourceData) {
        targetRoot.subdivide();
        for (final OctreeNode child : targetRoot.children()) {
            child.setBlockData(sourceData);
        }
        if (!(sourceData instanceof Slab slab)
                || slab.getType() == Slab.Type.DOUBLE) return;

        final boolean removeTop = slab.getType() == Slab.Type.BOTTOM;
        for (final OctreeNode child : targetRoot.children()) {
            final boolean topHalf = child.minY() >= 8;
            if (topHalf == removeTop) child.remove();
        }
    }

    /** Build the initial tree without resolving displays, for PDC reconstruction. */
    public void initLeavesDataOnly() {
        root.subdivide();
        for (OctreeNode child : root.children()) {
            child.setBlockData(this.originalBlockData);
        }
    }

    /**
     * 建立 8 個 depth=1 的葉子，使用指定的 BlockData（SculptMode 用）。
     */
    public void initLeaves(final BlockData leafBlockData) {
        root.subdivide();
        for (OctreeNode child : root.children()) {
            child.setBlockData(leafBlockData);
        }
    }

    /**
     * Initialize a newly placed SculptBlock with exactly one occupied cell.
     * This is intentionally data-only so entering SCULPTED spawns only the
     * final leaf instead of spawning a complete level and removing siblings.
     */
    public void initSingleCell(
            final int gx,
            final int gy,
            final int gz,
            final int targetDepth) {
        initSingleCell(gx, gy, gz, targetDepth, null);
    }

    /** Initialize one cell and optionally attach an indivisible held-head texture. */
    public void initSingleCell(
            final int gx,
            final int gy,
            final int gz,
            final int targetDepth,
            final PlayerHeadTexture playerHeadTexture) {
        if (state != State.COMPLETE || rootEntity != null || !root.isLeaf()) {
            throw new IllegalStateException(
                "Single-cell initialization requires a new COMPLETE SculptBlock");
        }
        if (targetDepth < 1 || targetDepth > maxDepth) {
            throw new IllegalArgumentException("targetDepth must be between 1 and " + maxDepth);
        }
        if (gx < 0 || gx >= 16 || gy < 0 || gy >= 16 || gz < 0 || gz >= 16) {
            throw new IllegalArgumentException("cell coordinates must be inside the 16x16x16 grid");
        }

        OctreeNode target = root;
        for (int depth = 1; depth <= targetDepth; depth++) {
            target.subdivide();
            final OctreeNode next = target.findLeaf(gx, gy, gz);
            for (final OctreeNode child : target.children()) {
                child.setBlockData(originalBlockData);
                if (child != next) child.remove();
            }
            target = next;
        }
        target.setPlayerHeadTexture(playerHeadTexture);
        rebuildCollisionTopology();
    }

    // ====================== 狀態轉換 ======================

    /** COMPLETE → SCULPTED */
    public void enterSculpted() {
        if (state == State.SCULPTED) return;
        final BlockData previousBackingData = pos.getBlock().getBlockData().clone();
        try {
            rootEntity = session.spawnRoot(centerLoc());
            for (OctreeNode leaf : root.collectLeaves()) {
                spawnLeafEntity(leaf);
            }
            state = State.SCULPTED;
            reconcileFillState();
            requestTextRender();
            syncPDC();
        } catch (final RuntimeException exception) {
            rollbackFailedEnter(previousBackingData, exception);
            throw exception;
        }
    }

    private void rollbackFailedEnter(
            final BlockData previousBackingData,
            final RuntimeException originalFailure) {
        try {
            session.destroyAll();
        } catch (final RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
        final List<OctreeNode> leaves = new ArrayList<>();
        root.collectAllLeaves(leaves);
        for (final OctreeNode leaf : leaves) {
            destroyLeafEntity(leaf);
        }
        try {
            cleanupShulkerEntities();
        } catch (final RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
        clearTextRender();
        rootEntity = null;
        state = State.COMPLETE;
        try {
            pos.getBlock().setBlockData(previousBackingData, false);
        } catch (final RuntimeException restoreFailure) {
            originalFailure.addSuppressed(restoreFailure);
        }
        try {
            onCleared.run();
        } catch (final RuntimeException unregisterFailure) {
            originalFailure.addSuppressed(unregisterFailure);
        }
    }

    /** 生成單一葉子實體並 addPassenger 到 rootEntity */
    private void spawnLeafEntity(OctreeNode leaf) {
        if (leaf == null || leaf.isRemoved()) return;
        if (leaf.playerHeadTexture() == null
                && displayMode == SculptDisplayMode.TEXT_DISPLAY) {
            leaf.head = null;
            return;
        }
        if (leaf.playerHeadTexture() == null
                && displayMode == SculptDisplayMode.AUTO) {
            final AutoDisplayMaterialStatus status = autoMaterialStatus(leaf);
            if (status == AutoDisplayMaterialStatus.UNKNOWN
                    || status == AutoDisplayMaterialStatus.LOADING
                    || status == AutoDisplayMaterialStatus.TRANSPARENT) {
                leaf.head = null;
                return;
            }
            final ChunkHead chunkHead = headResolver.headFor(leaf, this);
            if (!isResolvedHead(chunkHead)) {
                leaf.head = null;
                return;
            }
            attachLeafEntity(leaf, chunkHead);
            return;
        }
        attachLeafEntity(leaf, headResolver.headFor(leaf, this));
    }

    private void attachLeafEntity(
            final OctreeNode leaf,
            final ChunkHead chunkHead) {
        DisplayHandle handle = session.spawnRiding(
            rootEntity, centerLoc(), chunkHead.head(), chunkHead.transformation());
        leaf.head = chunkHead;
        leaf.attachHandle(handle);
        // 寫入 PDC 路徑，供 ChunkLoad 重建時配對 passenger → 葉子
        handle.setPDC(new org.bukkit.NamespacedKey("sculpt", "path"), leaf.pathAsString());
        handle.setPDC(new org.bukkit.NamespacedKey("sculpt", "type"), "leaf");
    }

    private AutoDisplayMaterialStatus autoMaterialStatus(
            final OctreeNode leaf) {
        if (textBlockRenderer == null) return AutoDisplayMaterialStatus.OPAQUE;
        final BlockData data = leaf.blockData() == null
            ? originalBlockData : leaf.blockData();
        return textBlockRenderer.autoMaterialStatus(this, data.getMaterial());
    }

    private static boolean isResolvedHead(final ChunkHead head) {
        return head != null && !head.placeholder();
    }

    private void destroyLeafEntity(final OctreeNode leaf) {
        if (leaf == null) return;
        if (leaf.handle() != null) {
            if (rootEntity != null) {
                session.removePassenger(rootEntity, leaf.handle());
            }
            session.destroy(leaf.handle());
            leaf.detachHandle();
        }
        leaf.head = null;
    }

    private void clearTextRender() {
        final TextBlockRenderHandle previous = textRenderHandle;
        textRenderHandle = null;
        if (previous != null) previous.despawn();
    }

    /**
     * Temporarily remove only the derived TextDisplay pixels. The root PDC,
     * octree, collision entities, and atomic player-head cells stay intact.
     * This lets clipboard integrations copy the model instead of thousands of
     * regenerable pixel entities.
     *
     * @return whether this block should be resumed afterwards
     */
    public boolean suspendTextRendering() {
        if (!displayMode.usesTextRenderer()
                || state != State.SCULPTED || despawned) {
            return false;
        }
        clearTextRender();
        return true;
    }

    /** Rebuild pixels removed by {@link #suspendTextRendering()}. */
    public void resumeTextRendering() {
        if (displayMode.usesTextRenderer()
                && state == State.SCULPTED && !despawned
                && textRenderHandle == null) {
            requestTextRender();
        }
    }

    private void requestTextRender() {
        if (!displayMode.usesTextRenderer()
                || textBlockRenderer == null
                || state != State.SCULPTED
                || rootEntity == null
                || despawned) {
            clearTextRender();
            return;
        }
        final TextBlockRenderHandle next = textBlockRenderer.render(this);
        final TextBlockRenderHandle previous = textRenderHandle;
        textRenderHandle = next;
        // The TextDisplay renderer keeps a long-lived handle and applies a
        // pixel-plane delta for ordinary edits. Only a renderer/display-mode
        // replacement returns a different handle and requires a full despawn.
        if (previous != null && previous != next) previous.despawn();
    }

    private void rebuildVisualRepresentation() {
        final List<OctreeNode> leaves = new ArrayList<>();
        root.collectAllLeaves(leaves);
        for (final OctreeNode leaf : leaves) destroyLeafEntity(leaf);
        clearTextRender();
        if (state != State.SCULPTED || rootEntity == null || despawned) return;
        for (final OctreeNode leaf : leaves) {
            if (!leaf.isRemoved()) spawnLeafEntity(leaf);
        }
        requestTextRender();
    }

    /**
     * 重新解析所有非 removed 葉子的頭顱（覆蓋 bake 完成的 skin）。
     * Bake 完成後呼叫此方法將 wool placeholder 替換為玩家頭顱。
     */
    public void reRender() {
        refreshLeafDisplays(true);
    }

    /** Whether an asynchronous result may still update this block's displays. */
    public boolean canRefreshDisplays() {
        return !despawned
            && state == State.SCULPTED
            && rootEntity != null
            && rootEntity.isValid();
    }

    /**
     * Restore native environmental lighting for the current TextDisplay
     * pixels. Geometry and entity identity are preserved; only legacy
     * brightness overrides are cleared.
     */
    public TextLightingRefreshResult refreshTextDisplayLighting() {
        if (!displayMode.usesTextRenderer()
                || textBlockRenderer == null
                || textRenderHandle == null
                || !canRefreshDisplays()) {
            return TextLightingRefreshResult.EMPTY;
        }
        return textBlockRenderer.refreshLighting(this);
    }

    /** Continue AUTO selection after an asynchronous model classification. */
    public void refreshAutoDisplay() {
        if (displayMode == SculptDisplayMode.AUTO) {
            refreshLeafDisplays(false);
        }
    }

    /** Continue AUTO selection only for cells using the resolved material. */
    public void refreshAutoDisplay(final Material material) {
        if (displayMode != SculptDisplayMode.AUTO
                || material == null
                || !canRefreshDisplays()) {
            return;
        }
        for (final OctreeNode leaf : root.collectLeaves()) {
            if (leaf.isRemoved() || leaf.playerHeadTexture() != null) continue;
            final BlockData data = leaf.blockData() == null
                ? originalBlockData : leaf.blockData();
            if (data.getMaterial() == material) reconcileAutoLeafEntity(leaf);
        }
        requestTextRender();
    }

    private void refreshLeafDisplays(final boolean suppressBakeRequests) {
        if (!canRefreshDisplays()) return;
        if (displayMode == SculptDisplayMode.TEXT_DISPLAY) {
            reconcileLeafDisplayEntities();
            requestTextRender();
            return;
        }
        if (displayMode == SculptDisplayMode.AUTO) {
            this.reRendering = suppressBakeRequests;
            try {
                for (final OctreeNode leaf : root.collectLeaves()) {
                    if (!leaf.isRemoved()) reconcileAutoLeafEntity(leaf);
                }
            } finally {
                this.reRendering = false;
            }
            requestTextRender();
            return;
        }
        this.reRendering = suppressBakeRequests;
        try {
            for (OctreeNode leaf : root.collectLeaves()) {
                if (leaf.isRemoved()) continue;
                ChunkHead newHead = headResolver.headFor(leaf, this);
                leaf.head = newHead;
                if (leaf.handle() != null) {
                    leaf.handle().setItemStack(newHead.head());
                    leaf.handle().setTransformation(newHead.transformation());
                }
            }
        } finally {
            this.reRendering = false;
        }
    }

    /** Recreate any missing leaf display after a partial save or entity loss. */
    public void repairDisplayEntities() {
        if (state != State.SCULPTED || rootEntity == null || despawned) return;
        reconcileLeafDisplayEntities();
        if (displayMode.usesTextRenderer()
                && textRenderHandle == null) requestTextRender();
    }

    private void reconcileLeafDisplayEntities() {
        final List<OctreeNode> leaves = new ArrayList<>();
        root.collectAllLeaves(leaves);
        for (final OctreeNode leaf : leaves) {
            if (leaf.isRemoved()) {
                destroyLeafEntity(leaf);
            } else if (displayMode == SculptDisplayMode.TEXT_DISPLAY
                    && leaf.playerHeadTexture() == null) {
                destroyLeafEntity(leaf);
            } else if (displayMode == SculptDisplayMode.AUTO
                    && leaf.playerHeadTexture() == null) {
                reconcileAutoLeafEntity(leaf);
            } else if (leaf.handle() == null) {
                spawnLeafEntity(leaf);
            }
        }
    }

    private void reconcileAutoLeafEntity(final OctreeNode leaf) {
        final AutoDisplayMaterialStatus status = autoMaterialStatus(leaf);
        if (status == AutoDisplayMaterialStatus.TRANSPARENT) {
            destroyLeafEntity(leaf);
            return;
        }
        if (status == AutoDisplayMaterialStatus.UNKNOWN
                || status == AutoDisplayMaterialStatus.LOADING) {
            if (leaf.handle() == null) leaf.head = null;
            return;
        }

        final ChunkHead resolved = headResolver.headFor(leaf, this);
        if (!isResolvedHead(resolved)) {
            // A persisted, already-renderable head remains valid while its
            // cold catalog entry is being prefetched. New AUTO cells never
            // expose the resolver's yellow-wool placeholder.
            if (leaf.handle() == null) leaf.head = null;
            return;
        }
        leaf.head = resolved;
        if (leaf.handle() != null) {
            leaf.handle().setItemStack(resolved.head());
            leaf.handle().setTransformation(resolved.transformation());
        } else {
            attachLeafEntity(leaf, resolved);
        }
    }

    /** Whether the derived TextDisplay renderer currently owns this leaf. */
    public boolean rendersLeafWithTextDisplay(final OctreeNode leaf) {
        if (leaf == null || leaf.isRemoved() || leaf.playerHeadTexture() != null) {
            return false;
        }
        return displayMode == SculptDisplayMode.TEXT_DISPLAY
            || (displayMode == SculptDisplayMode.AUTO && leaf.handle() == null);
    }

    /**
     * 重新解析單一葉子的頭顱並更新 ItemDisplay（材質變更後呼叫）。
     * 若葉子無實體（COMPLETE 或 removed），直接更新 head 但不 spawn。
     * 完成後重算 isMixed，若材質一致則嘗試自動 COMPLETE。
     */
    public void updateLeafDisplay(final OctreeNode leaf) {
        if (!leaf.isLeaf()) return;
        // 如果此葉子的父節點已被 coarsen（children = null），
        // 代表此葉子已從樹中移除，不應再生成實體。
        // 這發生在 restore() 觸發 coarsen 後，呼叫端仍持有已失效的 leaf 參考。
        if (leaf.parent() != null && !leaf.parent().isBranch()) return;
        if (displayMode == SculptDisplayMode.TEXT_DISPLAY
                && leaf.playerHeadTexture() == null) {
            destroyLeafEntity(leaf);
            if (!leaf.isRemoved()) requestTextRender();
            this.isMixed = recomputeMixedState();
            if (!isMixed) tryComplete();
            return;
        }
        if (displayMode == SculptDisplayMode.AUTO
                && leaf.playerHeadTexture() == null) {
            // This method represents a material update. Do not retain a head
            // belonging to the previous material while the new model loads.
            destroyLeafEntity(leaf);
            if (!leaf.isRemoved()) spawnLeafEntity(leaf);
            if (!leaf.isRemoved()) requestTextRender();
            this.isMixed = recomputeMixedState();
            if (!isMixed) tryComplete();
            return;
        }
        final ChunkHead newHead = headResolver.headFor(leaf, this);
        leaf.head = newHead;
        if (leaf.isRemoved()) return;
        if (leaf.handle() != null) {
            leaf.handle().setItemStack(newHead.head());
            leaf.handle().setTransformation(newHead.transformation());
        } else if (state == State.SCULPTED && rootEntity != null) {
            final DisplayHandle handle = session.spawnRiding(
                rootEntity, centerLoc(), newHead.head(), newHead.transformation());
            leaf.attachHandle(handle);
            handle.setPDC(new org.bukkit.NamespacedKey("sculpt", "path"), leaf.pathAsString());
            handle.setPDC(new org.bukkit.NamespacedKey("sculpt", "type"), "leaf");
        }
        if (displayMode.usesTextRenderer() && !leaf.isRemoved()) {
            requestTextRender();
        }
        this.isMixed = recomputeMixedState();
        if (!isMixed) tryComplete();  // 材質一致 → 嘗試自動完成
    }

    /** SCULPTED → COMPLETE */
    public boolean tryComplete() {
        if (state == State.COMPLETE) return true;
        if (isMixed) return false;
        // A held player-head texture has no equivalent vanilla BlockData; a
        // COMPLETE transition would silently discard its profile.
        if (containsPlayerHeadTexture(root)) return false;
        final BlockData completionData = completionBlockData();
        if (completionData == null) return false;

        // 移除所有 ItemDisplay（含 root 和殘留實體），並通知外層解除註冊
        despawn();

        // 恢復等價的原版方塊。完整填滿的單半磚會收斂成 double slab。
        pos.getBlock().setBlockData(completionData);

        markPDCDirty();
        return true;
    }

    /** Return the vanilla BlockData equivalent of the current occupancy. */
    private BlockData completionBlockData() {
        if (originalBlockData instanceof Slab slab) {
            return slabCompletionData(root, slab);
        }

        final OctreeNode[] rootKids = root.children();
        if (rootKids != null) {
            // root 尚有子節點（尚未 coarsen）→ 檢查全數就緒
            for (final OctreeNode child : rootKids) {
                if (child.isRemoved() || child.isBranch()) return null;
            }
            if (!allChildrenSameBlockData(root)) return null;
        }
        // rootKids == null 表示已經 coarsen 完成，直接過渡
        return originalBlockData.clone();
    }

    static BlockData slabCompletionData(
            final OctreeNode targetRoot,
            final Slab original) {
        if (!allOccupiedLeavesMatch(targetRoot, original)) return null;
        if (matchesSlabOccupancy(targetRoot, original.getType())) {
            return original.clone();
        }
        if (original.getType() != Slab.Type.DOUBLE
                && matchesSlabOccupancy(targetRoot, Slab.Type.DOUBLE)) {
            final Slab doubled = (Slab) original.clone();
            doubled.setType(Slab.Type.DOUBLE);
            return doubled;
        }
        return null;
    }

    static boolean matchesSlabOccupancy(
            final OctreeNode targetRoot,
            final Slab.Type type) {
        final List<OctreeNode> leaves = new ArrayList<>();
        targetRoot.collectAllLeaves(leaves);
        for (final OctreeNode leaf : leaves) {
            final boolean expectedOccupied = switch (type) {
                case BOTTOM -> leaf.minY() + leaf.side() <= 8;
                case TOP -> leaf.minY() >= 8;
                case DOUBLE -> true;
            };
            if (leaf.isRemoved() == expectedOccupied) return false;
        }
        return true;
    }

    private static boolean allOccupiedLeavesMatch(
            final OctreeNode targetRoot,
            final BlockData expected) {
        final List<OctreeNode> leaves = new ArrayList<>();
        targetRoot.collectAllLeaves(leaves);
        for (final OctreeNode leaf : leaves) {
            if (leaf.isRemoved()) continue;
            final BlockData actual = leaf.blockData() == null
                ? expected : leaf.blockData();
            if (!expected.equals(actual)) return false;
        }
        return true;
    }

    // ====================== subdivide ======================

    public boolean subdivide(OctreeNode node) {
        if (!node.isLeaf() || node.depth() >= maxDepth) return false;
        // Held-head cells are atomic even while removed. Replacement paths
        // must explicitly discard the old material before refining the space.
        if (node.playerHeadTexture() != null) return false;
        if (state == State.COMPLETE) enterSculpted();

        // 純資料操作
        node.setPlayerHeadTexture(null);
        node.subdivide();

        // 對每個子葉子生成目前顯示策略所需的實體
        for (OctreeNode child : node.children()) {
            spawnLeafEntity(child);
        }

        // Despawn 父節點實體
        destroyLeafEntity(node);
        // mergedHead 不再使用（漏洞 #8 修復：coarsen 時重新解析）

        markPDCDirty();
        requestTextRender();
        return true;
    }

    // ====================== coarsen ======================

    public void coarsen(OctreeNode node) {
        if (!node.isBranch()) return;
        if (isMixed) return;  // 混合材質不可粗化
        // Do not merge several atomic head cells into a larger cell.
        if (containsPlayerHeadTexture(node)) return;
        // 只有所有子葉的 BlockData 一致時才合併
        if (!allChildrenSameBlockData(node)) return;

        // 在 coarsen 前先取得子節點的共同 BlockData（coarsen 後 children 會消失）
        BlockData childrenBlockData = null;
        for (OctreeNode child : node.children()) {
            if (child.blockData() != null) {
                childrenBlockData = child.blockData();
                break;
            }
        }

        // Despawn 子節點目前的顯示實體
        for (OctreeNode child : node.children()) {
            destroyLeafEntity(child);
        }

        // 合併節點
        node.coarsen();

        // 更新合併後節點的 BlockData 為子節點的共同 BlockData
        // （避免 SculptMode 重新上色後合併時，使用到過時的父節點 BlockData）
        if (childrenBlockData != null) {
            node.setBlockData(childrenBlockData);
        }

        if (node.parent() == null) {
            // 合併到 root：所有 depth=1 葉子已合併 → 直接過渡回 COMPLETE
            tryComplete();
            markPDCDirty();
            return;
        }

        spawnLeafEntity(node);

        // 向上遞迴傳播
        if (node.parent().isBranch()
                && allChildrenNonRemoved(node.parent())
                && allChildrenSameBlockData(node.parent())) {
            coarsen(node.parent());
        }

        markPDCDirty();
        requestTextRender();
    }

    private boolean allChildrenNonRemoved(OctreeNode branch) {
        for (OctreeNode child : branch.children()) {
            if (!child.isLeaf() || child.isRemoved()) return false;
        }
        return true;
    }

    /**
     * 檢查分支下所有子葉的 BlockData 是否完全一致。
     * 不一致時跳過合併，避免方向性方塊（logs 等）合併為錯誤材質。
     */
    private boolean allChildrenSameBlockData(OctreeNode branch) {
        if (!branch.isBranch()) return true;
        BlockData first = null;
        PlayerHeadTexture firstTexture = null;
        boolean firstSet = false;
        for (OctreeNode child : branch.children()) {
            BlockData bd = child.blockData();
            if (!firstSet) {
                first = bd;
                firstTexture = child.playerHeadTexture();
                firstSet = true;
            } else if (!Objects.equals(bd, first)
                    || !Objects.equals(
                        child.playerHeadTexture(), firstTexture)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsPlayerHeadTexture(final OctreeNode node) {
        if (node.isLeaf()) return node.playerHeadTexture() != null;
        for (final OctreeNode child : node.children()) {
            if (containsPlayerHeadTexture(child)) return true;
        }
        return false;
    }

    // ====================== remove ======================

    public void remove(OctreeNode node) {
        if (!node.isLeaf() || node.isRemoved()) return;

        if (state == State.COMPLETE) {
            enterSculpted();
        }

        node.remove();

        destroyLeafEntity(node);

        markPDCDirty();

        if (!isBatchOperation()) {
            reconcileCollisionState();
            // 全部 root 子葉已移除 → 設為空氣
            checkEmptyAndClear();
            if (!despawned) requestTextRender();
        }
    }

    /**
     * 若八元樹中所有葉子都已移除，將 BARRIER 設為 AIR，
     * 清除所有殘留實體。適用於任何 depth（grid=2/4/8/16）。
     */
    private void checkEmptyAndClear() {
        if (state != State.SCULPTED || isBatchOperation()) return;
        if (!allLeavesAtAnyDepthRemoved()) return;
        // 所有葉子已移除 → 將 BARRIER 設為 AIR，並清除整個 SculptBlock
        pos.getBlock().setType(org.bukkit.Material.AIR);
        despawn();
    }

    private boolean allLeavesAtAnyDepthRemoved() {
        java.util.List<OctreeNode> leaves = new java.util.ArrayList<>();
        root.collectAllLeaves(leaves);
        for (OctreeNode leaf : leaves) {
            if (!leaf.isRemoved()) return false;
        }
        return true;
    }

    // ====================== restore ======================

    public void restore(OctreeNode node) {
        if (!node.isLeaf() || !node.isRemoved()) return;

        node.restore();

        spawnLeafEntity(node);

        if (node.parent() != null && node.parent().isBranch()
                && allChildrenNonRemoved(node.parent())) {
            coarsen(node.parent());
        }

        // A half slab is complete when its original four depth-1 cells are
        // restored; the deliberately empty half must not prevent returning to
        // the vanilla slab block.
        if (!isBatchOperation()
                && originalBlockData instanceof Slab
                && tryComplete()) {
            markPDCDirty();
            return;
        }

        if (!isBatchOperation()) {
            reconcileCollisionState();
            requestTextRender();
        }

        markPDCDirty();
    }

    // ====================== 座標輔助 ======================

    /** 世界方塊中心位置（rootEntity 和所有葉子實體的位置）。 */
    public Location centerLoc() {
        return new Location(world, centerX, centerY, centerZ);
    }

    // ====================== 便利方法 ======================

    public OctreeNode leafAt(int gx, int gy, int gz) {
        return root.findLeaf(gx, gy, gz);
    }

    public void ensureDepthAt(int gx, int gy, int gz, int targetDepth) {
        if (targetDepth > maxDepth) targetDepth = maxDepth;
        OctreeNode node = leafAt(gx, gy, gz);
        while (node.depth() < targetDepth) {
            if (!subdivide(node)) return;
            node = leafAt(gx, gy, gz);
        }
    }

    /** Return the node covering a coordinate at {@code targetDepth}, if present. */
    public OctreeNode nodeAtDepth(
            final int gx, final int gy, final int gz, final int targetDepth) {
        if (targetDepth < 0 || targetDepth > maxDepth) return null;
        OctreeNode node = root;
        while (node.depth() < targetDepth) {
            if (node.isLeaf()) return node;
            final int half = node.side() / 2;
            int child = 0;
            if (gx >= node.minX() + half) child |= 4;
            if (gy >= node.minY() + half) child |= 2;
            if (gz >= node.minZ() + half) child |= 1;
            node = node.children()[child];
        }
        return node;
    }

    /**
     * Refine an entirely removed coarse leaf without spawning transient
     * displays. All new leaves remain removed; the returned target can then be
     * assigned a material and restored.
     */
    public OctreeNode refineRemovedLeafAt(
            final OctreeNode coarseLeaf,
            final int gx, final int gy, final int gz,
            final int targetDepth) {
        if (coarseLeaf == null || !coarseLeaf.isLeaf()
                || !coarseLeaf.isRemoved()
                || targetDepth < coarseLeaf.depth()
                || targetDepth > maxDepth) return null;
        OctreeNode node = coarseLeaf;
        while (node.depth() < targetDepth) {
            node.setPlayerHeadTexture(null);
            node.subdivide();
            for (final OctreeNode child : node.children()) child.remove();
            node = node.findLeaf(gx, gy, gz);
        }
        markPDCDirty();
        return node;
    }

    /**
     * Collapse a completely empty refined region to one removed leaf. This is
     * used before placing an atomic head at a coarser player resolution.
     */
    public OctreeNode collapseRemovedRegionAt(
            final int gx, final int gy, final int gz,
            final int targetDepth) {
        final OctreeNode node = nodeAtDepth(gx, gy, gz, targetDepth);
        if (node == null || node.depth() != targetDepth) return null;
        if (node.isLeaf()) return node.isRemoved() ? node : null;

        final List<OctreeNode> leaves = new ArrayList<>();
        node.collectAllLeaves(leaves);
        if (leaves.isEmpty() || leaves.stream().anyMatch(leaf -> !leaf.isRemoved())) {
            return null;
        }
        BlockData inherited = null;
        for (final OctreeNode leaf : leaves) {
            if (inherited == null && leaf.blockData() != null) {
                inherited = leaf.blockData();
            }
            destroyLeafEntity(leaf);
        }
        node.coarsen();
        if (inherited != null) node.setBlockData(inherited);
        node.setPlayerHeadTexture(null);
        node.remove();
        markPDCDirty();
        return node;
    }

    public void beginBatchOperation() {
        batchOperationDepth++;
    }

    public void endBatchOperation() {
        if (batchOperationDepth <= 0) {
            throw new IllegalStateException("No SculptBlock batch operation is active");
        }
        batchOperationDepth--;
        if (batchOperationDepth == 0 && !despawned) {
            if (originalBlockData instanceof Slab && tryComplete()) return;
            reconcileCollisionState();
            checkEmptyAndClear();
            if (!despawned) requestTextRender();
        }
    }

    private boolean isBatchOperation() {
        return batchOperationDepth > 0;
    }

    /**
     * 移除 {@code scope} 分支下所有非 keep cell 的葉子，只保留該 cell。
     * {@code scope} 是 ensureDepthAt 細分的起始節點（subdivide 後變為分支）。
     * 前置條件：keep cell 已透過 ensureDepthAt 細分到正確深度。
     */
    public void keepOnlyCellAt(int gx, int gy, int gz, OctreeNode scope) {
        OctreeNode keep = leafAt(gx, gy, gz);
        if (keep == null || scope == null || !scope.isBranch()) return;
        java.util.List<OctreeNode> allLeaves = new java.util.ArrayList<>();
        scope.collectAllLeaves(allLeaves);
        int kx = keep.minX() + keep.side() / 2;
        int ky = keep.minY() + keep.side() / 2;
        int kz = keep.minZ() + keep.side() / 2;
        beginBatchOperation();
        try {
            for (OctreeNode lf : allLeaves) {
                if (lf.minX() + lf.side() / 2 != kx
                 || lf.minY() + lf.side() / 2 != ky
                 || lf.minZ() + lf.side() / 2 != kz) {
                    if (!lf.isRemoved()) remove(lf);
                }
            }
        } finally {
            endBatchOperation();
        }
    }

    // ====================== 整批移除/還原 ======================

    public void removeRange(int minX, int minY, int minZ, int side) {
        beginBatchOperation();
        try {
            collectLeavesInRange(root, minX, minY, minZ, side)
                .forEach(this::remove);
        } finally {
            endBatchOperation();
        }
    }

    public void restoreRange(int minX, int minY, int minZ, int side) {
        beginBatchOperation();
        try {
            collectLeavesInRange(root, minX, minY, minZ, side)
                .forEach(l -> { if (l.isRemoved()) restore(l); });
        } finally {
            endBatchOperation();
        }
    }

    private List<OctreeNode> collectLeavesInRange(
            OctreeNode node, int minX, int minY, int minZ, int side) {
        List<OctreeNode> out = new ArrayList<>();
        collectLeavesInRange(node, minX, minY, minZ, side, out);
        return out;
    }

    private void collectLeavesInRange(
            OctreeNode node, int minX, int minY, int minZ, int side,
            List<OctreeNode> out) {
        if (node.isLeaf()) {
            if (node.minX() < minX + side && node.minX() + node.side() > minX
                && node.minY() < minY + side && node.minY() + node.side() > minY
                && node.minZ() < minZ + side && node.minZ() + node.side() > minZ) {
                out.add(node);
            }
        } else {
            for (OctreeNode child : node.children()) {
                collectLeavesInRange(child, minX, minY, minZ, side, out);
            }
        }
    }

    // ====================== 既有方法 ======================

    public void revert() {
        if (despawned) return;
        if (fillMode != FillMode.BARRIER) {
            cleanupShulkerEntities();
            fillMode = FillMode.BARRIER;
        }
        despawn();
        pos.getBlock().setBlockData(originalBlockData);
    }

    /**
     * Remove all shulker entities and the Interaction click proxy.
     * Called during fill changes, revert, and despawn.
     */
    public void cleanupShulkerEntities() {
        removeClickProxy();
        clearCollisionEntities();
    }

    public void despawn() {
        if (despawned) return;
        despawned = true;
        // Always clean derived physical entities. This also repairs a stale
        // or partially migrated BARRIER block that still owns old proxies.
        cleanupShulkerEntities();
        clearTextRender();
        // 直接遍歷八元樹移除所有葉子實體
        // Keep the explicit leaf pass for corrupted/legacy sessions; normal
        // handles are also removed from the session registry by destroy().
        final java.util.List<OctreeNode> allLeaves = new java.util.ArrayList<>();
        root.collectAllLeaves(allLeaves);
        for (final OctreeNode leaf : allLeaves) {
            destroyLeafEntity(leaf);
        }
        // 移除 root 實體
        if (rootEntity != null) {
            session.destroy(rootEntity);
        }
        session.destroyAll();
        rootEntity = null;
        state = State.COMPLETE;
        onCleared.run();
    }

    public void spawnFor(Player viewer) {
        if (state != State.SCULPTED) return;
        session.setVisible(viewer, true);
    }

    public void hideFor(Player viewer) {
        // no-op: 預設對所有人可見
    }

    // ====================== PDC 同步 ======================

    private boolean pdcDirty = false;

    public void markPDCDirty() {
        pdcDirty = true;
        // Folia cancels plugin tasks as disable begins, so a global shutdown
        // flush cannot safely touch every region. Persist while already on the
        // owning region instead; regular Paper keeps the configured batching.
        if (FoliaScheduler.isFolia()) flushPDC();
    }

    public void flushPDC() {
        if (!pdcDirty || rootEntity == null) return;
        pdcDirty = false;

        rootEntity.setPDC(new org.bukkit.NamespacedKey("sculpt", "type"), "root");
        rootEntity.setPDC(new org.bukkit.NamespacedKey("sculpt", "original_block"),
            originalBlockData.getAsString());
        rootEntity.setPDC(new org.bukkit.NamespacedKey("sculpt", "matched_variant"),
            matchedVariantKey);
        rootEntity.setPDC(new org.bukkit.NamespacedKey("sculpt", "rotation"),
            blockRotation.x() + "," + blockRotation.y() + ","
            + blockRotation.z() + "," + blockRotation.w());
        rootEntity.setPDC(new org.bukkit.NamespacedKey("sculpt", "tint_argb"),
            Integer.toString(tintArgb));
        rootEntity.setPDC(new org.bukkit.NamespacedKey("sculpt", "fill_mode"),
            fillMode.id());
        rootEntity.setPDC(new org.bukkit.NamespacedKey("sculpt", "display_mode"),
            displayMode.id());
        rootEntity.removePDC(
            new org.bukkit.NamespacedKey("sculpt", "shulker_mode"));

        // 單次深度優先遍歷收集所有 PDC 資料（取代原先 3 次分開遍歷 + stream）
        final StringBuilder removedBuf = new StringBuilder();
        final StringBuilder subdividedBuf = new StringBuilder();
        final StringBuilder leafDataBuf = new StringBuilder();
        collectPDCData(root, removedBuf, subdividedBuf, leafDataBuf);

        rootEntity.setPDC(new org.bukkit.NamespacedKey("sculpt", "removed"),
            removedBuf.toString());
        rootEntity.setPDC(new org.bukkit.NamespacedKey("sculpt", "subdivided"),
            subdividedBuf.toString());
        rootEntity.setPDC(new org.bukkit.NamespacedKey("sculpt", "leaf_block_data"),
            leafDataBuf.toString());
        rootEntity.setPDCBytes(
            new org.bukkit.NamespacedKey("sculpt", "leaf_player_heads"),
            PlayerHeadTextureCodec.encode(root));
    }

    /**
     * 單次深度優先遍歷收集移除路徑、細分路徑、leaf blockData 覆寫。
     * 取代原先 collectSubdivided + collectAllLeaves + stream 的三次分開遍歷。
     */
    private void collectPDCData(OctreeNode node, StringBuilder removed,
            StringBuilder subdivided, StringBuilder leafData) {
        if (node.isBranch()) {
            // 分支節點（非 root）：記錄 subdivided 路徑
            if (node.parent() != null) {
                if (subdivided.length() > 0) subdivided.append('|');
                subdivided.append(node.pathAsString());
            }
            for (OctreeNode child : node.children()) {
                collectPDCData(child, removed, subdivided, leafData);
            }
        } else {
            // 葉子節點
            final String path = node.pathAsString();
            if (node.isRemoved()) {
                if (removed.length() > 0) removed.append('|');
                removed.append(path);
            }
            // Removed cells retain their replacement material too; otherwise a
            // restart could pair a stored head texture with the original block.
            final BlockData bd = node.blockData();
            if (bd != null && !bd.equals(originalBlockData)) {
                if (leafData.length() > 0) leafData.append('|');
                leafData.append(path).append('=').append(bd.getAsString());
            }
        }
    }

    public void syncPDC() {
        pdcDirty = true;
        flushPDC();
    }
}
