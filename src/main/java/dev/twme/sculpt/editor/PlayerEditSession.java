package dev.twme.sculpt.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import javax.annotation.Nullable;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.core.FaceDir;
import dev.twme.sculpt.core.HeadResolver;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.core.VariantResolution;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.transport.bukkit.BukkitTransportSession;
import dev.twme.sculpt.util.InteractionSpawner;
import dev.twme.sculpt.util.MessageUtil;

/**
 * 玩家編輯會話。不綁定特定 SculptBlock。
 * 玩家開啟 Sculpt mode 後建立 session，可以在世界中任何位置進行編輯。
 */
public final class PlayerEditSession {

    private static final double MAX_TRACE_DISTANCE = 5.0;

    public final Player player;
    private int playerGrid;
    private boolean ended = false;

    // Hover 狀態
    private VirtualGridHit hoveredHit;
    private SculptBlock hoveredSculpt;
    // 透過空洞穿透時，記錄要直接 restore 的 cell 座標（grid=16 空間），供 onRightClick 使用
    private int gapRestoreX = -1, gapRestoreY = -1, gapRestoreZ = -1;
    // 被穿透的原 SculptBlock（與 hoveredSculpt 分離，不受 hover 狀態影響）
    private SculptBlock gapRestoreSculpt;
    // 空洞後方的一般方塊（供左鍵穿透時建立 SculptBlock）
    private Block gapBehindBlock;
    private VirtualGridHit gapBehindHit;

    // Plugin lookup hooks (set by plugin)
    private Function<BlockPosKey, SculptBlock> blockLookup;
    private BiFunction<BlockPosKey, SculptBlock, Boolean> registerBlock;
    private BiConsumer<BlockPosKey, SculptBlock> unregisterBlock;
    private ToIntFunction<Player> playerGridSupplier;
    private Predicate<Block> canEditBlock;
    private Consumer<SculptBlock> configureBlock;

    public PlayerEditSession(Player player, int playerGrid) {
        this.player = player;
        this.playerGrid = playerGrid;
    }

    /** 由 plugin 設定，用於查詢/註冊 SculptBlock。 */
    public void setPluginHooks(
            Function<BlockPosKey, SculptBlock> blockLookup,
            BiFunction<BlockPosKey, SculptBlock, Boolean> registerBlock,
            BiConsumer<BlockPosKey, SculptBlock> unregisterBlock,
            ToIntFunction<Player> playerGridSupplier,
            Predicate<Block> canEditBlock) {
        setPluginHooks(blockLookup, registerBlock, unregisterBlock,
            playerGridSupplier, canEditBlock, ignored -> {});
    }

    /** Configure newly-created blocks with the player's current strategies. */
    public void setPluginHooks(
            Function<BlockPosKey, SculptBlock> blockLookup,
            BiFunction<BlockPosKey, SculptBlock, Boolean> registerBlock,
            BiConsumer<BlockPosKey, SculptBlock> unregisterBlock,
            ToIntFunction<Player> playerGridSupplier,
            Predicate<Block> canEditBlock,
            Consumer<SculptBlock> configureBlock) {
        this.blockLookup = blockLookup;
        this.registerBlock = registerBlock;
        this.unregisterBlock = unregisterBlock;
        this.playerGridSupplier = playerGridSupplier;
        this.canEditBlock = canEditBlock;
        this.configureBlock = configureBlock == null ? ignored -> {} : configureBlock;
    }

    /**
     * 回傳當前的 playerGrid（每次查詢都從 plugin 讀取最新值，
     * 確保 /sculpt resolution <N> 立即生效）。
     */
    private int livePlayerGrid() {
        if (playerGridSupplier != null) {
            return playerGridSupplier.applyAsInt(player);
        }
        return playerGrid;
    }

    // ===== Hover =====

    /** 執行 3D DDA，更新 hoveredHit + hoveredSculpt。 */
    public void tickHover() {
        tickHoverAndGetGrid();
    }

    /** 執行 hover 並回傳本次使用的解析度，供顯示更新共用。 */
    int tickHoverAndGetGrid() {
        clearGapRestoreTarget();
        this.gapBehindBlock = null;
        this.gapBehindHit = null;
        final int pg = livePlayerGrid();
        final HoverEngine.ViewRay ray = HoverEngine.ViewRay.from(player);

        // Paper's entity ray trace can omit an Interaction when the ray starts
        // inside its hitbox. This happens in particular when a scaled-down
        // player's eye is inside an AIR-backed SculptBlock. Resolve that one
        // registry position first so its internal cells cannot be skipped in
        // favour of a normal block farther along the ray.
        if (traceContainingSculpt(ray, pg)) return pg;

        // Resolve the closest real block or Sculpt Interaction in one world query.
        // Partial adaptive blocks are AIR, so their Interaction wins naturally;
        // a solid block in front of one wins without a second blocker trace.
        RayTraceResult targetResult = player.getWorld().rayTrace(
            ray.eye(),
            ray.direction(),
            MAX_TRACE_DISTANCE,
            FluidCollisionMode.NEVER,
            false,
            0,
            e -> e instanceof Interaction interaction
                && InteractionSpawner.isSculptInteraction(interaction)
        );

        if (targetResult != null
                && targetResult.getHitEntity() instanceof Interaction interaction) {
            final Location blockLoc = interaction.getLocation().toBlockLocation();
            final SculptBlock parent = (blockLookup != null)
                ? blockLookup.apply(BlockPosKey.of(blockLoc))
                : null;
            if (parent != null && parent.usesEntityInteraction()) {
                // The Interaction's hit surface Y doesn't correspond to the cell the player
                // is looking AT (the player's eye height skews the surface hit position).
                // Instead of computing cell indices from the hit surface, use the proper 3D
                // DDA ray tracer (HoverEngine.traceSculpt) which walks the octree correctly
                // from the player's eye position and direction.
                final Block airBlock = blockLoc.getBlock();
                final Vector hitPos = targetResult.getHitPosition();
                final double lx = Math.clamp(hitPos.getX() - blockLoc.getX(), 0.0, 1.0 - 1e-6);
                final double ly = Math.clamp(hitPos.getY() - blockLoc.getY(), 0.0, 1.0 - 1e-6);
                final double lz = Math.clamp(hitPos.getZ() - blockLoc.getZ(), 0.0, 1.0 - 1e-6);
                final FaceDir entryFace = computeFaceFromLocal(lx, ly, lz);

                final VirtualGridHit ddaHit = HoverEngine.traceSculpt(
                    ray, parent, pg, airBlock, entryFace);
                if (ddaHit != null) {
                    this.hoveredHit = ddaHit;
                    this.hoveredSculpt = parent;
                    return pg;
                }
                final boolean gapFound = traceWorldGap(ray, parent, pg);
                if (!gapFound) {
                    this.hoveredHit = null;
                    this.hoveredSculpt = null;
                }
                return pg;
            }
            // A stale proxy must not hide a valid block behind it.
            targetResult = player.getWorld().rayTraceBlocks(
                ray.eye(), ray.direction(), MAX_TRACE_DISTANCE,
                FluidCollisionMode.NEVER, false);
        }

        final Block targetBlock = targetResult == null ? null : targetResult.getHitBlock();
        if (targetBlock == null || isAir(targetBlock.getType())) {
            this.hoveredHit = null;
            this.hoveredSculpt = null;
            return pg;
        }

        final FaceDir hitFace = hitFace(targetResult, ray, targetBlock);
        final BlockPosKey key = BlockPosKey.of(targetBlock);

        // 查是否已有 SculptBlock
        SculptBlock sculpt = (blockLookup != null) ? blockLookup.apply(key) : null;

        if (sculpt != null) {
            // 若該位置的方塊已被取代（非 BARRIER），視為一般方塊
            if (targetBlock.getType() != org.bukkit.Material.BARRIER) {
                this.hoveredHit = HoverEngine.traceNormalAtHit(
                    pg, targetBlock, hitFace, targetResult.getHitPosition());
                this.hoveredSculpt = null;
                return pg;
            }
            // SculptBlock 路徑 → 3D DDA
            VirtualGridHit hit = HoverEngine.traceSculpt(
                ray, sculpt, pg, targetBlock, hitFace);
            if (hit != null) {
                this.hoveredHit = hit;
                this.hoveredSculpt = sculpt;
                return pg;
            }
            final boolean found = traceWorldGap(ray, sculpt, pg);
            if (!found) {
                this.hoveredHit = null;
                this.hoveredSculpt = null;
            }
        } else {
            // 一般方塊路徑 → 表面 2D 網格
            this.hoveredHit = HoverEngine.traceNormalAtHit(
                pg, targetBlock, hitFace, targetResult.getHitPosition());
            this.hoveredSculpt = null;
        }
        return pg;
    }

    /**
     * Trace the active SculptBlock containing the ray origin, if any. Returning
     * {@code true} means the containing position handled the complete ray,
     * including traversal through an empty path into later world blocks.
     */
    private boolean traceContainingSculpt(
            final HoverEngine.ViewRay ray,
            final int pg) {
        if (blockLookup == null) return false;

        final SculptBlock containing = blockLookup.apply(BlockPosKey.of(ray.eye()));
        if (containing == null || containing.state != SculptBlock.State.SCULPTED) {
            return false;
        }

        final Block containingBlock = containing.pos.getBlock();
        final FaceDir entryFace = HoverEngine.computeHitFaceSlab(
            ray, containingBlock);
        final VirtualGridHit hit = HoverEngine.traceSculpt(
            ray, containing, pg, containingBlock, entryFace);
        if (hit != null) {
            this.hoveredHit = hit;
            this.hoveredSculpt = containing;
            return true;
        }

        if (!traceWorldGap(ray, containing, pg)) {
            this.hoveredHit = null;
            this.hoveredSculpt = null;
        }
        return true;
    }

    private static FaceDir hitFace(
            final RayTraceResult result,
            final HoverEngine.ViewRay ray,
            final Block block) {
        final BlockFace face = result == null ? null : result.getHitBlockFace();
        if (face == null) return HoverEngine.computeHitFaceSlab(ray, block);
        return switch (face) {
            case DOWN -> FaceDir.DOWN;
            case UP -> FaceDir.UP;
            case NORTH -> FaceDir.NORTH;
            case SOUTH -> FaceDir.SOUTH;
            case WEST -> FaceDir.WEST;
            case EAST -> FaceDir.EAST;
            default -> HoverEngine.computeHitFaceSlab(ray, block);
        };
    }

    public VirtualGridHit getHoveredHit() { return hoveredHit; }
    public SculptBlock getHoveredSculpt() { return hoveredSculpt; }
    public boolean hasGapRestoreTarget() { return gapRestoreX >= 0 && gapRestoreSculpt != null; }
    // ===== 點擊 =====

    /**
     * 左鍵點擊。
     * - 有 SculptBlock → §3 Case A/B/C
     * - 無 SculptBlock → 建立新的
     */
    public void onLeftClick(HeadResolver headResolver) {
        if (hoveredHit == null) return;
        final int pg = livePlayerGrid();

        // Resolution 1 only operates on an existing SculptBlock. Normal blocks
        // are left to vanilla interaction by the listener; retaining this guard
        // also keeps direct callers from creating or deleting normal blocks.
        if (pg == 1) {
            final SculptBlock wholeBlock = hoveredSculpt;
            if (wholeBlock == null) return;
            final Block target = wholeBlock.pos.getBlock();
            if (!canEdit(target)) return;
            wholeBlock.removeRange(0, 0, 0, 16);
            return;
        }

        // 穿透空洞時左鍵：對後方方塊開始雕刻，並立即移除穿透對應的 cell。
        if (gapRestoreX >= 0) {
            if (gapBehindBlock != null && !isAir(gapBehindBlock.getType())
                    && !gapBehindBlock.getType().name().contains("WATER")
                    && !gapBehindBlock.getType().name().contains("LAVA")) {
                if (!canEdit(gapBehindBlock)) return;
                BlockPosKey behindKey = BlockPosKey.of(gapBehindBlock);
                SculptBlock behindSb = (blockLookup != null) ? blockLookup.apply(behindKey) : null;
                if (behindSb == null) {
                    behindSb = createSculptBlockAt(gapBehindBlock, headResolver);
                }
                if (behindSb != null) {
                    final VirtualGridHit behindHit = gapBehindHit;
                    if (behindHit != null) {
                        removeAtHit(behindSb, pg, behindHit);
                    }
                }
            }
            return;
        }

        SculptBlock sb = hoveredSculpt;
        final Block targetBlock = sb == null ? hoveredHit.block() : sb.pos.getBlock();
        if (!canEdit(targetBlock)) return;
        if (sb == null) {
            sb = createSculptBlockAt(hoveredHit.block(), headResolver);
            if (sb == null) return;
        }

        removeAtHit(sb, pg, hoveredHit);
    }

    private void removeAtHit(SculptBlock sb, int pg, VirtualGridHit hit) {
        int pgDepth = Integer.bitCount(pg - 1); // log2(playerGrid)
        int side = 16 / pg;
        int cx = hit.grid16CenterX(pg);
        int cy = hit.grid16CenterY(pg);
        int cz = hit.grid16CenterZ(pg);

        OctreeNode leaf = sb.leafAt(cx, cy, cz);
        if (leaf == null) return;

        // 中心點 leaf 已移除，但此 player-grid 區域內可能還有其他
        // 更精細的非移除 cell（DDA 已確認有內容才傳回 hit）。
        // 使用 removeRange 移除整個 player-grid 區域的所有殘留葉子，
        // remove() 會跳過已移除的葉子，僅處理實際需移除的。
        if (leaf.isRemoved()) {
            if (leaf.depth() > pgDepth) {
                sb.removeRange(
                    hit.grid16MinX(pg),
                    hit.grid16MinY(pg),
                    hit.grid16MinZ(pg),
                    side);
            }
            return;
        }

        // A texture copied from a held player head is one atomic cell. A
        // finer player grid removes that entire cell instead of subdividing it.
        if (leaf.playerHeadTexture() != null && leaf.depth() < pgDepth) {
            sb.remove(leaf);
            return;
        }

        if (leaf.depth() == pgDepth) {
            sb.remove(leaf);
        } else if (leaf.depth() < pgDepth) {
            sb.ensureDepthAt(cx, cy, cz, pgDepth);
            OctreeNode target = sb.leafAt(cx, cy, cz);
            if (target != null) sb.remove(target);
        } else {
            sb.removeRange(
                hit.grid16MinX(pg),
                hit.grid16MinY(pg),
                hit.grid16MinZ(pg),
                side);
        }
    }

    /**
     * Restore the cell adjacent to the current hit. A non-null material
     * replaces the restored cell's material (Sculpt mode); {@code null}
     * preserves its existing material.
     */
    public void onRightClick(@Nullable final BlockData replacementData) {
        onRightClickCell(replacementData == null
            ? null : CellMaterial.block(replacementData));
    }

    /** Restore using block data plus an optional held player-head texture. */
    public void onRightClickCell(@Nullable final CellMaterial replacement) {
        if (hoveredHit == null) return;

        if (restoreGapTarget(replacement)) return;
        if (hoveredSculpt == null) return;

        final int gridSize = livePlayerGrid();
        final GridCell adjacentCell = GridCell.adjacentTo(hoveredHit);
        if (!adjacentCell.isInside(gridSize)) return;

        restoreCellMaterialAt(
            hoveredSculpt,
            adjacentCell.centerX(gridSize),
            adjacentCell.centerY(gridSize),
            adjacentCell.centerZ(gridSize),
            gridSize,
            replacement);
    }

    /**
     * Restore a cell at a grid-16 center. When {@code replacementData} is
     * present, every restored leaf receives that material.
     */
    public void restoreCellAt(
            final SculptBlock block,
            final int centerX,
            final int centerY,
            final int centerZ,
            final int gridSize,
            @Nullable final BlockData replacementData) {
        restoreCellMaterialAt(block, centerX, centerY, centerZ, gridSize,
            replacementData == null ? null : CellMaterial.block(replacementData));
    }

    public void restoreCellMaterialAt(
            final SculptBlock block,
            final int centerX,
            final int centerY,
            final int centerZ,
            final int gridSize,
            @Nullable final CellMaterial replacement) {
        final int gridDepth = Integer.bitCount(gridSize - 1);
        final int side = GridCell.OCTREE_GRID_SIZE / gridSize;
        final OctreeNode leaf = block.leafAt(centerX, centerY, centerZ);
        if (leaf == null || !leaf.isRemoved()) return;
        if (!canEdit(block.pos.getBlock())) return;

        if (replacement != null && replacement.isTexturedPlayerHead()) {
            restoreAtomicHeadCell(
                block, leaf, centerX, centerY, centerZ, gridDepth, replacement);
            return;
        }

        // Restoring an already stored atomic head through Sculpt mode keeps its
        // original size even when the current player grid is finer.
        if (replacement == null && leaf.playerHeadTexture() != null
                && leaf.depth() <= gridDepth) {
            block.restore(leaf);
            return;
        }

        if (leaf.depth() == gridDepth) {
            restoreLeaf(block, leaf, replacement);
        } else if (leaf.depth() < gridDepth) {
            restoreSubdividedCell(
                block, leaf, centerX, centerY, centerZ,
                gridDepth, replacement);
        } else {
            restoreCellRange(
                block, centerX, centerY, centerZ,
                side, replacement);
        }
    }

    private void restoreAtomicHeadCell(
            final SculptBlock block,
            final OctreeNode currentLeaf,
            final int centerX,
            final int centerY,
            final int centerZ,
            final int targetDepth,
            final CellMaterial replacement) {
        OctreeNode target = currentLeaf;
        if (currentLeaf.depth() < targetDepth) {
            target = block.refineRemovedLeafAt(
                currentLeaf, centerX, centerY, centerZ, targetDepth);
        } else if (currentLeaf.depth() > targetDepth) {
            target = block.collapseRemovedRegionAt(
                centerX, centerY, centerZ, targetDepth);
        }
        if (target == null || !target.isLeaf() || !target.isRemoved()) return;
        replacement.applyTo(target);
        refreshMixedState(block);
        block.restore(target);
        refreshMixedState(block);
    }

    private boolean restoreGapTarget(@Nullable final CellMaterial replacement) {
        if (!hasGapRestoreTarget()) return false;

        final SculptBlock block = gapRestoreSculpt;
        final int gridSize = livePlayerGrid();
        final OctreeNode target = block.leafAt(
            gapRestoreX, gapRestoreY, gapRestoreZ);
        if (target != null && target.isRemoved() && target.depth() > 0) {
            restoreCellMaterialAt(
                block, gapRestoreX, gapRestoreY, gapRestoreZ,
                gridSize, replacement);
        }
        clearGapRestoreTarget();
        return true;
    }

    private void restoreSubdividedCell(
            final SculptBlock block,
            final OctreeNode coarseLeaf,
            final int centerX,
            final int centerY,
            final int centerZ,
            final int targetDepth,
            @Nullable final CellMaterial replacement) {
        final OctreeNode target = block.refineRemovedLeafAt(
            coarseLeaf, centerX, centerY, centerZ, targetDepth);
        if (target == null || !target.isRemoved()) return;
        if (replacement != null) {
            replacement.applyTo(target);
            refreshMixedState(block);
        }
        block.restore(target);
        if (replacement != null) refreshMixedState(block);
    }

    private void restoreCellRange(
            final SculptBlock block,
            final int centerX,
            final int centerY,
            final int centerZ,
            final int side,
            @Nullable final CellMaterial replacement) {
        final int minX = centerX / side * side;
        final int minY = centerY / side * side;
        final int minZ = centerZ / side * side;
        if (replacement != null) {
            setRemovedLeafMaterialInRange(
                block, minX, minY, minZ, side, replacement);
            refreshMixedState(block);
        }
        block.restoreRange(minX, minY, minZ, side);
        if (replacement != null) refreshMixedState(block);
    }

    private void setRemovedLeafMaterialInRange(
            final SculptBlock block,
            final int minX,
            final int minY,
            final int minZ,
            final int side,
            final CellMaterial replacement) {
        final List<OctreeNode> leaves = new ArrayList<>();
        block.root.collectAllLeaves(leaves);
        for (final OctreeNode leaf : leaves) {
            if (overlaps(leaf, minX, minY, minZ, side) && leaf.isRemoved()) {
                replacement.applyTo(leaf);
            }
        }
    }

    private static boolean overlaps(
            final OctreeNode leaf,
            final int minX,
            final int minY,
            final int minZ,
            final int side) {
        return leaf.minX() < minX + side && leaf.minX() + leaf.side() > minX
            && leaf.minY() < minY + side && leaf.minY() + leaf.side() > minY
            && leaf.minZ() < minZ + side && leaf.minZ() + leaf.side() > minZ;
    }

    private static void restoreLeaf(
            final SculptBlock block,
            final OctreeNode leaf,
            @Nullable final CellMaterial replacement) {
        if (replacement != null) {
            replacement.applyTo(leaf);
            refreshMixedState(block);
        }
        block.restore(leaf);
        if (replacement != null) refreshMixedState(block);
    }

    private static void refreshMixedState(final SculptBlock block) {
        if (!block.despawned) block.setMixed(block.recomputeMixedState());
    }

    private void clearGapRestoreTarget() {
        gapRestoreX = gapRestoreY = gapRestoreZ = -1;
        gapRestoreSculpt = null;
    }

    /** Resolution 1 right-click: restore the whole hovered SculptBlock. */
    public boolean restoreWholeBlock() {
        if (hoveredSculpt == null || !canEdit(hoveredSculpt.pos.getBlock())) return false;
        hoveredSculpt.revert();
        clearHoverState();
        return true;
    }

    // ===== 生命週期 =====

    private SculptBlock createSculptBlockAt(Block block, HeadResolver headResolver) {
        BlockData data = block.getBlockData();
        String material = block.getType().name();
        if (material.contains("WATER") || material.contains("LAVA")) return null;

        final int pg = livePlayerGrid();
        VariantResolution resolution = headResolver.resolveVariant(data, pg);
        BukkitTransportSession transportSession = new BukkitTransportSession(
            block.getWorld());
        SculptBlock sb = new SculptBlock(
            block.getWorld(), block.getLocation(), data,
            resolution.matchedVariant(), resolution.rotation(),
            transportSession, headResolver);
        if (configureBlock != null) configureBlock.accept(sb);
        sb.setOnCleared(() -> {
            if (unregisterBlock != null) {
                unregisterBlock.accept(BlockPosKey.of(sb.pos), sb);
            }
        });
        sb.initFromOriginalBlockShape();

        if (registerBlock != null
                && !registerBlock.apply(BlockPosKey.of(block.getLocation()), sb)) {
            MessageUtil.sendTranslatedActionBar(player, "command.sculpt_edit.limit_reached");
            return null;
        }
        return sb;
    }

    public void end() {
        ended = true;
        clearHoverState();
    }
    public boolean isEnded() { return ended; }
    public int getPlayerGrid() { return livePlayerGrid(); }
    public void setPlayerGrid(int grid) { this.playerGrid = grid; }

    private boolean canEdit(final Block block) {
        return canEditBlock == null || canEditBlock.test(block);
    }

    private void clearHoverState() {
        hoveredHit = null;
        hoveredSculpt = null;
        gapRestoreX = gapRestoreY = gapRestoreZ = -1;
        gapRestoreSculpt = null;
        gapBehindBlock = null;
        gapBehindHit = null;
    }

    /**
     * Walk the world block grid after a ray exits a SculptBlock gap. This
     * detects both AIR-backed adaptive SculptBlocks and normal solid blocks
     * without repeatedly invoking the world's block ray tracer.
     */
    boolean traceWorldGap(final Player player, final SculptBlock sculpt, final int pg) {
        return traceWorldGap(HoverEngine.ViewRay.from(player), sculpt, pg);
    }

    private boolean traceWorldGap(
            final HoverEngine.ViewRay ray,
            final SculptBlock sculpt,
            final int pg) {
        final Location eye = ray.eye();
        final Vector direction = ray.direction();
        final double dx = direction.getX();
        final double dy = direction.getY();
        final double dz = direction.getZ();

        // Exit point from sculp's block bounds [0,1]³ using slab intersection
        final double bx = sculpt.pos.getBlockX();
        final double by = sculpt.pos.getBlockY();
        final double bz = sculpt.pos.getBlockZ();
        final double ox = eye.getX() - bx, oy = eye.getY() - by, oz = eye.getZ() - bz;

        double tExit = 1e9;
        for (int axis = 0; axis < 3; axis++) {
            final double o = (axis == 0) ? ox : (axis == 1) ? oy : oz;
            final double d = (axis == 0) ? dx : (axis == 1) ? dy : dz;
            if (d == 0) continue;
            final double t1 = (0 - o) / d;
            final double t2 = (1 - o) / d;
            final double exit = Math.max(t1, t2);
            if (exit < tExit) tExit = Math.max(exit, 0);
        }
        if (tExit >= 1e9 || tExit > MAX_TRACE_DISTANCE) return false;

        // Start slightly past the initial SculptBlock's exit surface.
        final double startT = tExit + 0.05;
        final double startX = eye.getX() + dx * startT;
        final double startY = eye.getY() + dy * startT;
        final double startZ = eye.getZ() + dz * startT;

        // 3D DDA on world block grid (1×1×1 cells)
        final int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        final int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        final int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        if (stepX == 0 && stepY == 0 && stepZ == 0) return false;

        int gx = (int) Math.floor(startX);
        int gy = (int) Math.floor(startY);
        int gz = (int) Math.floor(startZ);

        // If start is exactly on a boundary, step into the next cell
        if (startX == gx && stepX > 0) gx++;
        if (startY == gy && stepY > 0) gy++;
        if (startZ == gz && stepZ > 0) gz++;
        if (startX == gx + 1 && stepX < 0) gx--;
        if (startY == gy + 1 && stepY < 0) gy--;
        if (startZ == gz + 1 && stepZ < 0) gz--;

        final double tDeltaX = stepX == 0
            ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        final double tDeltaY = stepY == 0
            ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        final double tDeltaZ = stepZ == 0
            ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double tMaxX = distanceToNextBoundary(startX, gx, stepX, dx);
        double tMaxY = distanceToNextBoundary(startY, gy, stepY, dy);
        double tMaxZ = distanceToNextBoundary(startZ, gz, stepZ, dz);

        final org.bukkit.World world = sculpt.world;
        final String worldName = world.getName();
        SculptBlock lastPassed = sculpt;
        final int maxSteps = (int) MAX_TRACE_DISTANCE * 2;

        for (int step = 0; step < maxSteps; step++) {
            final BlockPosKey posKey = new BlockPosKey(worldName, gx, gy, gz);
            final SculptBlock cellSculpt = blockLookup == null
                ? null : blockLookup.apply(posKey);
            final Block cellBlock = world.getBlockAt(gx, gy, gz);

            // Check SculptBlock at current grid cell
            if (cellSculpt != null && cellSculpt != lastPassed) {
                final FaceDir entryFace = HoverEngine.computeHitFaceSlab(ray, cellBlock);
                final VirtualGridHit cellHit = HoverEngine.traceSculpt(
                    ray, cellSculpt, pg, cellBlock, entryFace);
                if (cellHit != null) {
                    this.hoveredHit = cellHit;
                    this.hoveredSculpt = cellSculpt;
                    return true;
                }
                // DDA passes through this one too → continue walking
                lastPassed = cellSculpt;
            }

            // Check if this grid cell is a solid block (non-AIR)
            if (!isAir(cellBlock.getType())) {
                // Solid block. If it's an active SculptBlock (BARRIER mode),
                // blockLookup already caught it above.
                if (cellBlock.getType() != org.bukkit.Material.BARRIER
                        || cellSculpt == null) {
                    // Respect the actual collision shape. A ray travelling
                    // through the empty half of a slab must continue to the
                    // next world cell instead of treating it as a full cube.
                    final RayTraceResult blockHit = cellBlock.rayTrace(
                        eye, direction, MAX_TRACE_DISTANCE,
                        FluidCollisionMode.NEVER);
                    if (blockHit != null) {
                        final FaceDir hitFace = hitFace(
                            blockHit, ray, cellBlock);
                        final VirtualGridHit normalHit =
                            HoverEngine.traceNormalAtHit(
                                pg, cellBlock, hitFace,
                                blockHit.getHitPosition());
                        if (normalHit == null) return false;
                        final int side = 16 / pg;

                        // Only the SculptBlock directly touching the clicked
                        // surface owns the cell that should be restored.
                        if (lastPassed != null
                                && isFaceAdjacent(lastPassed, cellBlock)) {
                            // Recompute the exit t for lastPassed for gap
                            // restore coordinates.
                            final double lbx = lastPassed.pos.getBlockX();
                            final double lby = lastPassed.pos.getBlockY();
                            final double lbz = lastPassed.pos.getBlockZ();
                            final double lox = eye.getX() - lbx;
                            final double loy = eye.getY() - lby;
                            final double loz = eye.getZ() - lbz;
                            double lExit = 1e9;
                            for (int axis = 0; axis < 3; axis++) {
                                final double o = (axis == 0) ? lox
                                    : (axis == 1) ? loy : loz;
                                final double d = (axis == 0) ? dx
                                    : (axis == 1) ? dy : dz;
                                if (d == 0) continue;
                                final double t1 = (0 - o) / d;
                                final double t2 = (1 - o) / d;
                                lExit = Math.min(lExit, Math.max(t1, t2));
                            }
                            final double eX = eye.getX() - lbx + dx * lExit;
                            final double eY = eye.getY() - lby + dy * lExit;
                            final double eZ = eye.getZ() - lbz + dz * lExit;
                            final int pgx = Math.min(
                                Math.max((int) (eX * pg), 0), pg - 1);
                            final int pgy = Math.min(
                                Math.max((int) (eY * pg), 0), pg - 1);
                            final int pgz = Math.min(
                                Math.max((int) (eZ * pg), 0), pg - 1);
                            this.gapRestoreX = pgx * side + side / 2;
                            this.gapRestoreY = pgy * side + side / 2;
                            this.gapRestoreZ = pgz * side + side / 2;
                            this.gapRestoreSculpt = lastPassed;
                            this.gapBehindBlock = cellBlock;
                            this.gapBehindHit = normalHit;
                        }

                        this.hoveredHit = normalHit;
                        this.hoveredSculpt = null;
                        return true;
                    }
                }
            }

            // Step DDA to next grid cell
            final double nextT = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (!Double.isFinite(nextT)) return false;
            if (tMaxX == nextT) {
                gx += stepX;
                tMaxX += tDeltaX;
                if (gx < -30_000_000 || gx > 30_000_000) return false;
            }
            if (tMaxY == nextT) {
                gy += stepY;
                tMaxY += tDeltaY;
                if (gy < -64 || gy > 320) return false;
            }
            if (tMaxZ == nextT) {
                gz += stepZ;
                tMaxZ += tDeltaZ;
                if (gz < -30_000_000 || gz > 30_000_000) return false;
            }

            // Safety limit
            final double dist = Math.abs(gx - sculpt.pos.getBlockX())
                              + Math.abs(gy - sculpt.pos.getBlockY())
                              + Math.abs(gz - sculpt.pos.getBlockZ());
            if (dist > MAX_TRACE_DISTANCE * 2) return false;
        }
        return false;
    }

    private static double distanceToNextBoundary(
            final double start,
            final int cell,
            final int step,
            final double direction) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        final double boundary = step > 0 ? cell + 1.0 : cell;
        return (boundary - start) / direction;
    }

    private static boolean isFaceAdjacent(
            final SculptBlock sculpt, final Block block) {
        return Math.abs(block.getX() - sculpt.pos.getBlockX())
            + Math.abs(block.getY() - sculpt.pos.getBlockY())
            + Math.abs(block.getZ() - sculpt.pos.getBlockZ()) == 1;
    }

    private static boolean isAir(final Material material) {
        return material == Material.AIR
            || material == Material.CAVE_AIR
            || material == Material.VOID_AIR;
    }

    /**
     * Compute the block face direction from a local hit position on a 1×1×1
     * Interaction hitbox (or block AABB). Determines which face of the cube
     * the ray entered by finding the axis with the smallest distance to a
     * cube boundary (0 or 1).
     *
     * @param lx local X in [0, 1]
     * @param ly local Y in [0, 1]
     * @param lz local Z in [0, 1]
     * @return the face whose boundary was hit (e.g. lx ~ 0 → WEST, lx ~ 1 → EAST)
     */
    private static FaceDir computeFaceFromLocal(final double lx, final double ly, final double lz) {
        final double ax = Math.min(lx, 1.0 - lx);
        final double ay = Math.min(ly, 1.0 - ly);
        final double az = Math.min(lz, 1.0 - lz);

        if (ax <= ay && ax <= az) return lx < 0.5 ? FaceDir.WEST : FaceDir.EAST;
        if (ay <= az) return ly < 0.5 ? FaceDir.DOWN : FaceDir.UP;
        return lz < 0.5 ? FaceDir.NORTH : FaceDir.SOUTH;
    }
}
