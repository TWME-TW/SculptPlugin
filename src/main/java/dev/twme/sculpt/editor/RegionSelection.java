package dev.twme.sculpt.editor;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * A region selection defined by two corner points.
 *
 * <p>Used by the wand tool to define a rectangular volume for
 * SculptBlock operations such as bulk shulker conversion.
 */
public record RegionSelection(Location pos1, Location pos2) {

    /** Whether both points are set and in the same world. */
    public boolean isValid() {
        return pos1 != null && pos2 != null
            && pos1.getWorld() != null
            && pos1.getWorld().equals(pos2.getWorld());
    }

    /** World of the selection (null if not valid). */
    public World world() {
        return pos1 != null ? pos1.getWorld() : null;
    }

    // ---- Bounds ----

    public int minX() {
        return Math.min(pos1.getBlockX(), pos2.getBlockX());
    }

    public int minY() {
        return Math.min(pos1.getBlockY(), pos2.getBlockY());
    }

    public int minZ() {
        return Math.min(pos1.getBlockZ(), pos2.getBlockZ());
    }

    public int maxX() {
        return Math.max(pos1.getBlockX(), pos2.getBlockX());
    }

    public int maxY() {
        return Math.max(pos1.getBlockY(), pos2.getBlockY());
    }

    public int maxZ() {
        return Math.max(pos1.getBlockZ(), pos2.getBlockZ());
    }

    /** Volume in blocks. */
    public long volume() {
        return (long) (maxX() - minX() + 1)
             * (maxY() - minY() + 1)
             * (maxZ() - minZ() + 1);
    }

    /**
     * Check whether the given block position is within this selection.
     */
    public boolean contains(final int x, final int y, final int z) {
        return x >= minX() && x <= maxX()
            && y >= minY() && y <= maxY()
            && z >= minZ() && z <= maxZ();
    }

    /**
     * Check whether the given block position is within this selection.
     */
    public boolean contains(final Location loc) {
        return loc.getWorld() != null && loc.getWorld().equals(world())
            && contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
