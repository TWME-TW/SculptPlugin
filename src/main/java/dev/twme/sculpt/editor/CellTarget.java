package dev.twme.sculpt.editor;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.joml.Vector3d;
import org.joml.Vector3f;

import dev.twme.sculpt.core.SculptBlock;

/**
 * The cell under a player's cursor at one resolution, in global cell
 * coordinates ({@code block * grid + local}).
 *
 * @param faceX  outward normal of the face the cursor entered through
 * @param sculpt the SculptBlock containing the cell, or {@code null}
 * @param block  the world block containing the cell
 */
public record CellTarget(
        World world,
        int grid,
        long x,
        long y,
        long z,
        int faceX,
        int faceY,
        int faceZ,
        SculptBlock sculpt,
        Block block
) {

    public static CellTarget of(final CellTracer.Result result, final int grid) {
        final VirtualGridHit hit = result.hit();
        final Block block = hit.block();
        return new CellTarget(block.getWorld(), grid,
            (long) block.getX() * grid + hit.pgx(),
            (long) block.getY() * grid + hit.pgy(),
            (long) block.getZ() * grid + hit.pgz(),
            hit.face().dx, hit.face().dy, hit.face().dz,
            result.sculpt(), block);
    }

    public long adjacentX() {
        return x + faceX;
    }

    public long adjacentY() {
        return y + faceY;
    }

    public long adjacentZ() {
        return z + faceZ;
    }

    /** The targeted cell, or the empty cell in front of it. */
    public VoxelBox cell(final boolean adjacent) {
        final int side = 16 / grid;
        final long cx = adjacent ? adjacentX() : x;
        final long cy = adjacent ? adjacentY() : y;
        final long cz = adjacent ? adjacentZ() : z;
        return VoxelBox.of(cx * side, cy * side, cz * side, side);
    }

    /** World-space center of the targeted or adjacent cell. */
    public Vector3d center(final boolean adjacent) {
        return cell(adjacent).center();
    }

    /** World-space minimum corner of the targeted or adjacent cell. */
    public Vector3f min(final boolean adjacent) {
        return cell(adjacent).min();
    }

    /** World-space maximum corner of the targeted or adjacent cell. */
    public Vector3f max(final boolean adjacent) {
        return cell(adjacent).max();
    }
}
