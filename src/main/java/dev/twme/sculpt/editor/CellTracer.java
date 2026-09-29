package dev.twme.sculpt.editor;

import java.util.function.Function;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import dev.twme.sculpt.core.FaceDir;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.util.InteractionSpawner;

/**
 * Finds the grid cell under a player's cursor at a given resolution.
 *
 * <p>The ray passes through empty cells of SculptBlocks, enters AIR-backed
 * SculptBlocks through their Interaction proxies, and respects the collision
 * shape of partial vanilla blocks. The tracer is stateless between calls;
 * {@link #trace()} returns the cell hit by the current view.</p>
 */
public final class CellTracer {

    /** Default reach, matching vanilla creative interaction range. */
    public static final double DEFAULT_REACH = 5.0;

    /**
     * @param hit    the cell and the face the ray entered through
     * @param sculpt the SculptBlock containing the cell, or {@code null} for a regular block
     */
    public record Result(VirtualGridHit hit, SculptBlock sculpt) {}

    private final Player player;
    private final int grid;
    private final double reach;
    private final Function<BlockPosKey, SculptBlock> blockLookup;

    // Results of the running trace.
    private VirtualGridHit hoveredHit;
    private SculptBlock hoveredSculpt;

    public CellTracer(
            final Player player,
            final int grid,
            final double reach,
            final Function<BlockPosKey, SculptBlock> blockLookup) {
        this.player = player;
        this.grid = grid;
        this.reach = reach;
        this.blockLookup = blockLookup;
    }

    /** Trace the player's current view; returns {@code null} when nothing is in reach. */
    public Result trace() {
        hoveredHit = null;
        hoveredSculpt = null;
        runTrace();
        return hoveredHit == null ? null : new Result(hoveredHit, hoveredSculpt);
    }

    /**
     * Continue a ray that has left {@code sculpt} through one of its empty
     * cells, as {@link #trace()} does internally. Exposed for tests.
     */
    Result traceBeyond(final SculptBlock sculpt) {
        hoveredHit = null;
        hoveredSculpt = null;
        if (!traceWorldGap(HoverEngine.ViewRay.from(player), sculpt, grid)) return null;
        return hoveredHit == null ? null : new Result(hoveredHit, hoveredSculpt);
    }

    private void runTrace() {
        final int pg = grid;
        final HoverEngine.ViewRay ray = HoverEngine.ViewRay.from(player);

        // Paper's entity ray trace can omit an Interaction when the ray starts
        // inside its hitbox. This happens in particular when a scaled-down
        // player's eye is inside an AIR-backed SculptBlock. Resolve that one
        // registry position first so its internal cells cannot be skipped in
        // favour of a normal block farther along the ray.
        if (traceContainingSculpt(ray, pg)) return;

        // Resolve the closest real block or Sculpt Interaction in one world query.
        // Partial adaptive blocks are AIR, so their Interaction wins naturally;
        // a solid block in front of one wins without a second blocker trace.
        RayTraceResult targetResult = player.getWorld().rayTrace(
            ray.eye(),
            ray.direction(),
            reach,
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
                    return;
                }
                final boolean gapFound = traceWorldGap(ray, parent, pg);
                if (!gapFound) {
                    this.hoveredHit = null;
                    this.hoveredSculpt = null;
                }
                return;
            }
            // A stale proxy must not hide a valid block behind it.
            targetResult = player.getWorld().rayTraceBlocks(
                ray.eye(), ray.direction(), reach,
                FluidCollisionMode.NEVER, false);
        }

        final Block targetBlock = targetResult == null ? null : targetResult.getHitBlock();
        if (targetBlock == null || isAir(targetBlock.getType())) {
            this.hoveredHit = null;
            this.hoveredSculpt = null;
            return;
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
                return;
            }
            // SculptBlock 路徑 → 3D DDA
            VirtualGridHit hit = HoverEngine.traceSculpt(
                ray, sculpt, pg, targetBlock, hitFace);
            if (hit != null) {
                this.hoveredHit = hit;
                this.hoveredSculpt = sculpt;
                return;
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
        if (tExit >= 1e9 || tExit > reach) return false;

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
        final int maxSteps = (int) reach * 2;

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
                        eye, direction, reach,
                        FluidCollisionMode.NEVER);
                    if (blockHit != null) {
                        final FaceDir hitFace = hitFace(
                            blockHit, ray, cellBlock);
                        final VirtualGridHit normalHit =
                            HoverEngine.traceNormalAtHit(
                                pg, cellBlock, hitFace,
                                blockHit.getHitPosition());
                        if (normalHit == null) return false;

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
            if (dist > reach * 2) return false;
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
