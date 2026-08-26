package dev.twme.sculpt.plugin;

import org.bukkit.Location;
import org.bukkit.block.Block;

/**
 * Simple position key for use as a hash key in SculptBlock tracking maps.
 * Combines world name and block coordinates.
 *
 * <p>Per DEVELOPMENT_PLAN.md §3, this is the canonical key type for the
 * {@code plugin.BlockPosKey} package slot.
 */
public record BlockPosKey(String world, int x, int y, int z) {

    public static BlockPosKey of(Location loc) {
        return new BlockPosKey(
                loc.getWorld() != null ? loc.getWorld().getName() : "",
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ());
    }

    public static BlockPosKey of(Block block) {
        return new BlockPosKey(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ());
    }
}
