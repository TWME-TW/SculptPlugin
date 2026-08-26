package dev.twme.sculpt.editor;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import dev.twme.sculpt.core.FaceDir;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.SculptBlock;

/**
 * 兩階段 HoverEngine。
 * <p>階段 1：標準世界方塊碰撞（Paper API）
 * <p>階段 2A：SculptBlock → 3D DDA
 * <p>階段 2B：一般方塊 → 表面 2D 網格
 */
public final class HoverEngine {

    private HoverEngine() {}

    /** Immutable-for-the-duration snapshot of one player view ray. */
    record ViewRay(Location eye, Vector direction) {
        static ViewRay from(final Player player) {
            final Location eye = player.getEyeLocation();
            return new ViewRay(eye, eye.getDirection());
        }
    }

    /**
     * 執行 hover ray-trace。
     * @param player      玩家
     * @param playerGrid  編輯解析度
     * @param maxDistance 最大距離
     * @return 命中結果，或 null
     */
    public static VirtualGridHit trace(Player player, int playerGrid, double maxDistance) {
        // === 階段 1：世界方塊碰撞 ===
        Block targetBlock = player.getTargetBlockExact((int) maxDistance,
            FluidCollisionMode.NEVER);
        if (targetBlock == null || targetBlock.getType().isAir()) return null;

        FaceDir hitFace = computeHitFaceSlab(player, targetBlock);

        // 一般方塊路徑（不查 sculpt block — 由呼叫端決定）
        return traceNormalBlock(ViewRay.from(player), playerGrid, targetBlock, hitFace);
    }

    /**
     * 對已知 SculptBlock 執行 3D DDA 遍歷。
     * @return 命中結果（pgx/pgy/pgz 在 SculptBlock 的 playerGrid 網格內），或 null
     */
    public static VirtualGridHit traceSculpt(
            Player player, SculptBlock sculpt, int playerGrid,
            Block hitBlock, FaceDir hitFace) {
        return traceSculpt(ViewRay.from(player), sculpt, playerGrid, hitBlock, hitFace);
    }

    static VirtualGridHit traceSculpt(
            ViewRay ray, SculptBlock sculpt, int playerGrid,
            Block hitBlock, FaceDir hitFace) {
        return traceSculptBlock(ray, sculpt, playerGrid, hitBlock, hitFace);
    }

    /**
     * 對一般方塊執行表面 2D 網格映射。
     */
    public static VirtualGridHit traceNormal(
            Player player, int playerGrid,
            Block hitBlock, FaceDir hitFace) {
        return traceNormal(ViewRay.from(player), playerGrid, hitBlock, hitFace);
    }

    static VirtualGridHit traceNormal(
            ViewRay ray, int playerGrid,
            Block hitBlock, FaceDir hitFace) {
        return traceNormalBlock(ray, playerGrid, hitBlock, hitFace);
    }

    /**
     * Map Paper's exact world-space collision hit into the virtual grid. The
     * sample is nudged just inside the hit face so a bottom slab's top surface
     * ({@code y=0.5}) selects the occupied lower cell instead of the empty
     * upper cell. This also remains correct for ordinary full cubes.
     */
    static VirtualGridHit traceNormalAtHit(
            final int playerGrid,
            final Block hitBlock,
            final FaceDir hitFace,
            final Vector hitPosition) {
        if (playerGrid == 1) {
            return new VirtualGridHit(0, 0, 0, hitFace, hitBlock);
        }
        if (hitPosition == null) return null;

        final double epsilon = 1.0e-7;
        final double localX = clampInside(
            hitPosition.getX() - hitBlock.getX() - hitFace.dx * epsilon);
        double localY = clampInside(
            hitPosition.getY() - hitBlock.getY() - hitFace.dy * epsilon);
        final double localZ = clampInside(
            hitPosition.getZ() - hitBlock.getZ() - hitFace.dz * epsilon);
        if (hitBlock.getBlockData() instanceof Slab slab) {
            if (slab.getType() == Slab.Type.BOTTOM) {
                localY = Math.min(localY, Math.nextDown(0.5));
            } else if (slab.getType() == Slab.Type.TOP) {
                localY = Math.max(localY, 0.5);
            }
        }
        return new VirtualGridHit(
            Math.min((int) (localX * playerGrid), playerGrid - 1),
            Math.min((int) (localY * playerGrid), playerGrid - 1),
            Math.min((int) (localZ * playerGrid), playerGrid - 1),
            hitFace, hitBlock);
    }

    private static double clampInside(final double value) {
        return Math.max(0.0, Math.min(Math.nextDown(1.0), value));
    }

    // ====================================================================
    // 階段 2A：SculptBlock 的 3D DDA
    // ====================================================================

    private static VirtualGridHit traceSculptBlock(
            ViewRay ray, SculptBlock sculpt, int playerGrid,
            Block hitBlock, FaceDir hitFace) {
        // playerGrid == 1 快速路徑：整格等於單一 cell，省略 DDA
        if (playerGrid == 1) {
            return new VirtualGridHit(0, 0, 0, hitFace, hitBlock);
        }

        final Location eye = ray.eye();
        final Vector direction = ray.direction();
        double bx = hitBlock.getX(), by = hitBlock.getY(), bz = hitBlock.getZ();

        double ox = eye.getX() - bx;
        double oy = eye.getY() - by;
        double oz = eye.getZ() - bz;
        double dx = direction.getX();
        double dy = direction.getY();
        double dz = direction.getZ();

        // Slab 法找到進入 block 立方體 [0,1]³ 的 t 值
        double tMin = -1e9, tMax = 1e9;
        FaceDir entryFace = hitFace;
        for (int axis = 0; axis < 3; axis++) {
            double o = (axis == 0) ? ox : (axis == 1) ? oy : oz;
            double d = (axis == 0) ? dx : (axis == 1) ? dy : dz;
            if (d == 0) {
                if (o < 0 || o > 1) return null;
                continue;
            }
            double t1 = (0 - o) / d;
            double t2 = (1 - o) / d;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tMin) {
                tMin = t1;
                // neg=true ⇔ ray 方向為負 ⇔ 進入面在正側 (axis=0 → EAST)
                boolean neg = d < 0;
                entryFace = faceForAxis(axis, neg);
            }
            if (t2 < tMax) tMax = t2;
        }
        if (tMax < tMin) return null;

        // 若玩家眼睛在 block 內部，從眼睛開始
        tMin = Math.max(tMin, 0);

        double entryX = ox + dx * tMin;
        double entryY = oy + dy * tMin;
        double entryZ = oz + dz * tMin;

        int gx = Math.min((int) (entryX * playerGrid), playerGrid - 1);
        int gy = Math.min((int) (entryY * playerGrid), playerGrid - 1);
        int gz = Math.min((int) (entryZ * playerGrid), playerGrid - 1);

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / (dx * playerGrid));
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / (dy * playerGrid));
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / (dz * playerGrid));

        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY
            : ((gx + (stepX > 0 ? 1 : 0)) / (double) playerGrid - entryX) / dx;
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY
            : ((gy + (stepY > 0 ? 1 : 0)) / (double) playerGrid - entryY) / dy;
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY
            : ((gz + (stepZ > 0 ? 1 : 0)) / (double) playerGrid - entryZ) / dz;

        FaceDir lastFace = entryFace;
        while (gx >= 0 && gx < playerGrid && gy >= 0 && gy < playerGrid && gz >= 0 && gz < playerGrid) {
            int side = 16 / playerGrid;
            int cx = gx * side + side / 2;
            int cy = gy * side + side / 2;
            int cz = gz * side + side / 2;
            OctreeNode leaf = sculpt.leafAt(cx, cy, cz);
            if (leaf != null && !leaf.isRemoved()) {
                return new VirtualGridHit(gx, gy, gz, lastFace, hitBlock);
            }
            // 當 player grid 比樹的精細度粗略時，中心點取樣可能落在
            // 已移除的細 cell，但同一個 player-grid 區域內仍有未移除的
            // 細 cell。遍歷整個區塊體積尋找非移除的葉子。
            if (leaf != null && leaf.isRemoved()) {
                int pgDepth = Integer.bitCount(playerGrid - 1);
                if (leaf.depth() > pgDepth) {
                    OctreeNode ancestor = leaf;
                    while (ancestor.depth() > pgDepth && ancestor.parent() != null) {
                        ancestor = ancestor.parent();
                    }
                    // ancestor 現在涵蓋至少 player-grid 區域大小
                    if (ancestor.isBranch()) {
                        for (OctreeNode child : ancestor.children()) {
                            boolean hasContent = child.isLeaf()
                                ? !child.isRemoved()
                                : !child.allRemoved();
                            if (hasContent) {
                                return new VirtualGridHit(gx, gy, gz, lastFace, hitBlock);
                            }
                        }
                    }
                }
            }

            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                gx += stepX; tMaxX += tDeltaX;
                // Entry face of the new cell: stepping +X → enter through -X (WEST); stepping -X → enter through +X (EAST)
                lastFace = faceForAxis(0, stepX < 0);
            } else if (tMaxY < tMaxZ) {
                gy += stepY; tMaxY += tDeltaY;
                lastFace = faceForAxis(1, stepY < 0);
            } else {
                gz += stepZ; tMaxZ += tDeltaZ;
                lastFace = faceForAxis(2, stepZ < 0);
            }
        }
        return null;
    }

    // ====================================================================
    // 階段 2B：一般方塊的表面 2D 網格
    // ====================================================================

    private static VirtualGridHit traceNormalBlock(
            ViewRay ray, int playerGrid,
            Block hitBlock, FaceDir hitFace) {
        // playerGrid == 1 快速路徑：整格等於單一 cell，省略表面映射
        if (playerGrid == 1) {
            return new VirtualGridHit(0, 0, 0, hitFace, hitBlock);
        }

        final Location eye = ray.eye();
        final Vector direction = ray.direction();
        double ox = eye.getX();
        double oy = eye.getY();
        double oz = eye.getZ();
        double dx = direction.getX();
        double dy = direction.getY();
        double dz = direction.getZ();

        // 計算 ray 與 hitFace 平面的交點
        double bx = hitBlock.getX(), by = hitBlock.getY(), bz = hitBlock.getZ();
        double t;
        switch (hitFace) {
            case UP:    t = (by + 1 - oy) / dy; break;
            case DOWN:  t = (by - oy) / dy; break;
            case EAST:  t = (bx + 1 - ox) / dx; break;
            case WEST:  t = (bx - ox) / dx; break;
            case SOUTH: t = (bz + 1 - oz) / dz; break;
            case NORTH: t = (bz - oz) / dz; break;
            default: return null;
        }
        double px = ox + dx * t;
        double py = oy + dy * t;
        double pz = oz + dz * t;

        // 計算交點在表面上的 2D 座標 (u, v) ∈ [0, 1]
        double u, v;
        switch (hitFace) {
            case UP:    u = (px - bx); v = (pz - bz); break;
            case DOWN:  u = (px - bx); v = (pz - bz); break;
            case EAST:  u = (pz - bz); v = (py - by); break;
            case WEST:  u = (pz - bz); v = (py - by); break;
            case SOUTH: u = (px - bx); v = (py - by); break;
            case NORTH: u = (px - bx); v = (py - by); break;
            default: return null;
        }
        u = Math.max(0, Math.min(0.9999, u));
        v = Math.max(0, Math.min(0.9999, v));

        int pgDiv = (int) (u * playerGrid);
        int pgDiv2 = (int) (v * playerGrid);

        int pgx, pgy, pgz;
        int outer = playerGrid - 1;
        switch (hitFace) {
            case UP:    pgx = pgDiv;  pgy = outer; pgz = pgDiv2; break;
            case DOWN:  pgx = pgDiv;  pgy = 0;     pgz = pgDiv2; break;
            case SOUTH: pgx = pgDiv;  pgy = pgDiv2; pgz = outer; break;
            case NORTH: pgx = pgDiv;  pgy = pgDiv2; pgz = 0;     break;
            case EAST:  pgx = outer;  pgy = pgDiv2; pgz = pgDiv; break;
            case WEST:  pgx = 0;      pgy = pgDiv2; pgz = pgDiv; break;
            default: return null;
        }
        return new VirtualGridHit(pgx, pgy, pgz, hitFace, hitBlock);
    }

    // ====================================================================
    // 公用工具
    // ====================================================================

    /**
     * 從軸向和方向推導 FaceDir。
     * @param axis 0=X, 1=Y, 2=Z
     * @param neg  true=進入面在該軸的負側（min 面），false=正側（max 面）
     */
    public static FaceDir faceForAxis(int axis, boolean neg) {
        // neg=true 表示 ray 方向為該軸負向 → 進入面在正側。
        //  X 正側 = EAST, 負側 = WEST
        //  Y 正側 = UP,   負側 = DOWN
        //  Z 正側 = SOUTH, 負側 = NORTH
        if (axis == 0) return neg ? FaceDir.EAST : FaceDir.WEST;
        if (axis == 1) return neg ? FaceDir.UP : FaceDir.DOWN;
        return neg ? FaceDir.SOUTH : FaceDir.NORTH;
    }

    /**
     * 使用 ray-AABB slab 演算法計算命中面。
     */
    public static FaceDir computeHitFaceSlab(Player player, Block block) {
        return computeHitFaceSlab(ViewRay.from(player), block);
    }

    static FaceDir computeHitFaceSlab(final ViewRay ray, final Block block) {
        final Location eye = ray.eye();
        final Vector direction = ray.direction();
        double cx = block.getX() + 0.5, cy = block.getY() + 0.5, cz = block.getZ() + 0.5;
        double ox = eye.getX() - cx;
        double oy = eye.getY() - cy;
        double oz = eye.getZ() - cz;
        double dx = direction.getX();
        double dy = direction.getY();
        double dz = direction.getZ();

        double tMin = -1e9;
        int minAxis = 0;
        boolean minNeg = false;
        for (int axis = 0; axis < 3; axis++) {
            double o = (axis == 0) ? ox : (axis == 1) ? oy : oz;
            double d = (axis == 0) ? dx : (axis == 1) ? dy : dz;
            if (d == 0) {
                if (o < -0.5 || o > 0.5) return FaceDir.UP;
                continue;
            }
            double t1 = (-0.5 - o) / d;
            double t2 = (0.5 - o) / d;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tMin) { tMin = t1; minAxis = axis; minNeg = d < 0; }
        }
        return faceForAxis(minAxis, minNeg);
    }
}
