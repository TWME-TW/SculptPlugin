package dev.twme.sculpt.integration;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;

/**
 * Cleanup utility for orphaned entities left behind after FAWE/WE
 * pastes a SculptBlock.
 *
 * <p>When FAWE copies a SculptBlock, its {@code Clipboard} stores the
 * root {@code ItemDisplay} along with all passenger leaf displays as
 * separate entities. During paste:</p>
 *
 * <ul>
 *   <li><b>Direct path</b> ({@code //paste -e}): {@code loadEntityRecursive}
 *   creates the root with correct passengers, <em>but</em> each leaf is also
 *   independently pasted, creating duplicate stand-alone leaf entities.</li>
 *   <li><b>Queue path</b> (default in FAWE): root entity loses its passengers,
 *   and each leaf becomes an independent entity without parent linkage.</li>
 * </ul>
 *
 * <p>This class removes leaf entities that are not passengers of the root,
 * and ensures the BARRIER block is present.</p>
 */
public final class SculptBlockCleaner {

    private static final NamespacedKey TYPE_KEY = new NamespacedKey("sculpt", "type");

    private SculptBlockCleaner() {
    }

    /**
     * Remove orphaned leaf {@code ItemDisplay} entities at the given location
     * that are NOT passengers of the given root display.
     *
     * <p>An "orphaned leaf" is an {@code ItemDisplay} with PDC
     * {@code sculpt:type=leaf} at the same block position as the root, but
     * not in the root's passenger list.</p>
     *
     * @param rootDisplay the root ItemDisplay (may be null if not found)
     * @param rootLoc     the block location of the SculptBlock
     */
    public static void cleanOrphanedLeaves(ItemDisplay rootDisplay, Location rootLoc) {
        if (rootDisplay == null) {
            // No root exists — remove all leaf entities at this position
            removeAllLeavesAt(rootLoc);
            return;
        }

        // Collect current passenger UUIDs
        Set<UUID> passengerIds = new HashSet<>();
        for (Entity passenger : rootDisplay.getPassengers()) {
            passengerIds.add(passenger.getUniqueId());
        }

        // Scan chunk for leaf entities at this block position
        World world = rootLoc.getWorld();
        if (world == null) return;

        for (Entity entity : world.getChunkAt(rootLoc).getEntities()) {
            if (entity.equals(rootDisplay)) continue;
            if (!(entity instanceof ItemDisplay leafDisplay)) continue;
            if (!isLeafEntity(leafDisplay)) continue;

            // Only process entities at the same block position
            if (!leafDisplay.getLocation().toBlockLocation().equals(rootLoc)) continue;

            // If it's not a passenger of the root → orphaned, remove it
            if (!passengerIds.contains(entity.getUniqueId())) {
                entity.remove();
            }
        }
    }

    /**
     * Remove orphaned leaves for several roots with one chunk entity scan.
     * This is used after bulk WorldEdit pastes, where scanning a high-resolution
     * chunk once per SculptBlock would otherwise dominate the server tick.
     */
    public static void cleanOrphanedLeaves(
            Chunk chunk, Collection<? extends ItemDisplay> rootDisplays) {
        if (chunk == null || rootDisplays == null || rootDisplays.isEmpty()) return;

        Map<BlockCoordinates, Set<UUID>> passengersByRoot = new HashMap<>();
        for (ItemDisplay root : rootDisplays) {
            if (root == null || !root.isValid()) continue;
            Set<UUID> passengerIds = new HashSet<>();
            for (Entity passenger : root.getPassengers()) {
                passengerIds.add(passenger.getUniqueId());
            }
            passengersByRoot.put(BlockCoordinates.of(root.getLocation()), passengerIds);
        }
        if (passengersByRoot.isEmpty()) return;

        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemDisplay leaf) || !isLeafEntity(leaf)) continue;
            Set<UUID> passengerIds = passengersByRoot.get(
                    BlockCoordinates.of(leaf.getLocation()));
            if (passengerIds != null && !passengerIds.contains(entity.getUniqueId())) {
                entity.remove();
            }
        }
    }

    /**
     * Ensure the BARRIER block exists at the given location.
     * Uses {@code setType(..., false)} to avoid triggering unnecessary physics.
     *
     * @param loc the block location
     */
    public static void ensureBarrierBlock(Location loc) {
        if (loc.getBlock().getType() != org.bukkit.Material.BARRIER) {
            loc.getBlock().setType(org.bukkit.Material.BARRIER, false);
        }
    }

    /**
     * Scan the given world {@link org.bukkit.Chunk} for a SculptBlock root
     * {@code ItemDisplay} at the given block position.
     *
     * @param world the world
     * @param x     block X coordinate
     * @param y     block Y coordinate
     * @param z     block Z coordinate
     * @return the root ItemDisplay, or null if not found
     */
    public static ItemDisplay findRootDisplay(World world, int x, int y, int z) {
        if (world == null) return null;
        Location targetLoc = new Location(world, x, y, z).toBlockLocation();
        for (Entity entity : world.getChunkAt(targetLoc).getEntities()) {
            if (!(entity instanceof ItemDisplay display)) continue;
            if (!display.getLocation().toBlockLocation().equals(targetLoc)) continue;
            if (isRootEntity(display)) return display;
        }
        return null;
    }

    /**
     * Check if the given ItemDisplay has PDC {@code sculpt:type=root}.
     */
    private static boolean isRootEntity(ItemDisplay display) {
        String type = display.getPersistentDataContainer()
                .get(TYPE_KEY, PersistentDataType.STRING);
        return "root".equals(type);
    }

    /**
     * Check if the given ItemDisplay has PDC {@code sculpt:type=leaf}.
     */
    private static boolean isLeafEntity(ItemDisplay display) {
        String type = display.getPersistentDataContainer()
                .get(TYPE_KEY, PersistentDataType.STRING);
        return "leaf".equals(type);
    }

    /**
     * Remove ALL leaf entities at the given location (used when no root exists).
     */
    private static void removeAllLeavesAt(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;
        for (Entity entity : world.getChunkAt(loc).getEntities()) {
            if (!(entity instanceof ItemDisplay leafDisplay)) continue;
            if (!isLeafEntity(leafDisplay)) continue;
            if (!leafDisplay.getLocation().toBlockLocation().equals(loc)) continue;
            entity.remove();
        }
    }

    private record BlockCoordinates(int x, int y, int z) {
        private static BlockCoordinates of(Location location) {
            return new BlockCoordinates(
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }
}
