package dev.twme.sculpt.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Guards synchronous world access against crossing Folia region ownership. */
public final class FoliaRegionGuard {

    private FoliaRegionGuard() {}

    /** Paper has one owning thread; Folia must own the target chunk right now. */
    public static boolean owns(final Location location) {
        final World world = location.getWorld();
        if (world == null) return false;
        if (!FoliaScheduler.isFolia()) return true;
        return Bukkit.isOwnedByCurrentRegion(location);
    }

    /**
     * Return whether every chunk touched by a block-coordinate cuboid belongs
     * to the currently ticking Folia region. A Folia region may own multiple
     * chunks, so this permits cross-chunk work when the platform says it is safe.
     */
    public static boolean ownsCuboid(
            final World world,
            final int minBlockX, final int minBlockZ,
            final int maxBlockX, final int maxBlockZ) {
        if (!FoliaScheduler.isFolia()) return true;
        final ChunkRange chunks = chunksTouched(
            minBlockX, minBlockZ, maxBlockX, maxBlockZ);
        return Bukkit.isOwnedByCurrentRegion(
            world, chunks.minX(), chunks.minZ(), chunks.maxX(), chunks.maxZ());
    }

    static ChunkRange chunksTouched(
            final int firstBlockX, final int firstBlockZ,
            final int secondBlockX, final int secondBlockZ) {
        final int minChunkX = Math.min(firstBlockX, secondBlockX) >> 4;
        final int maxChunkX = Math.max(firstBlockX, secondBlockX) >> 4;
        final int minChunkZ = Math.min(firstBlockZ, secondBlockZ) >> 4;
        final int maxChunkZ = Math.max(firstBlockZ, secondBlockZ) >> 4;
        return new ChunkRange(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    record ChunkRange(int minX, int minZ, int maxX, int maxZ) {}
}
