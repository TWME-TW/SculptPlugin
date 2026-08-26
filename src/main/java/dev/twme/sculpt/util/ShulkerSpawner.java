package dev.twme.sculpt.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Shulker;
import org.bukkit.persistence.PersistentDataType;

import dev.twme.sculpt.core.CollisionOctree;
import dev.twme.sculpt.core.SculptBlock;

/**
 * Utility for spawning and removing Shulker collision entities.
 *
 * <p>Each occupied leaf in a partial material-independent collision octree
 * gets one Shulker entity. A fully occupied root uses a BARRIER block instead.
 * Shulkers provide real collision boxes that scale with the canonical cube.
 *
 * <p>To prevent Minecraft's built-in Shulker surface-attachment logic
 * from pulling the entity to the nearest solid block, each Shulker is
 * made to ride an invisible "seat" entity (a BlockDisplay with AIR
 * block data) positioned at the exact cell position.
 */
public final class ShulkerSpawner {

    /** PDC key for shulker type identification. */
    public static final NamespacedKey SHULKER_TYPE_KEY =
        new NamespacedKey("sculpt", "type");

    private ShulkerSpawner() {
    }

    /**
     * Spawn a Shulker for the given octree leaf cell, together with an
     * invisible seat entity that the Shulker rides to prevent surface
     * attachment.
     *
     * <p>The Shulker is spawned 5 blocks above the cell position (safe
     * distance from any nearby player) and immediately mounted onto the
     * seat, which is at the exact cell position.  All properties (scale,
     * invisibility, AI=off, PDC markers) are applied inside the spawn
     * lambda so the entity never appears at default size.
     *
     * @param sculp the parent SculptBlock
     * @param leaf  the canonical collision leaf to place the shulker in
     * @return the spawned Shulker (the seat entity is made its vehicle)
     */
    public static Shulker spawn(final SculptBlock sculp,
                                final CollisionOctree.Node leaf) {
        if (!leaf.isOccupied() || !sculp.usesEntityCollision()) return null;

        final org.bukkit.World world = sculp.world;
        final int[] min = leaf.minCorner();
        final int side = leaf.side();
        final double cellSize = side / 16.0;

        // Cell corner in world coordinates (Shulker places at block integer)
        // XZ: center of cell (Shulker hitbox is centered on entity position)
        // Y: foot at cell bottom (Shulker Y = foot position)
        final double worldX = sculp.pos.getX() + (min[0] + side / 2.0) / 16.0;
        final double worldY = sculp.pos.getY() + (double) min[1] / 16.0;
        final double worldZ = sculp.pos.getZ() + (min[2] + side / 2.0) / 16.0;
        final Location spawnLoc = new Location(world, worldX, worldY, worldZ);

        // 1) Spawn invisible seat entity (BlockDisplay with AIR)
        final BlockDisplay seat = world.spawn(spawnLoc, BlockDisplay.class, s -> {
            s.setBlock(Material.AIR.createBlockData());
            s.setDisplayWidth(0);
            s.setDisplayHeight(0);
            s.setViewRange(0);
            s.setInvulnerable(true);
            s.getPersistentDataContainer().set(
                    SHULKER_TYPE_KEY, PersistentDataType.STRING, "shulker_seat");
            s.getPersistentDataContainer().set(
                    new NamespacedKey("sculpt", "path"),
                    PersistentDataType.STRING, leaf.path());
        });

        // 2) Spawn Shulker 5 blocks above the cell so it never collides
        //    with the player during the brief spawn → mount window.
        //    All properties are set in the lambda so the entity never
        //    appears at default size.
        final Location safeSpawnLoc = spawnLoc.clone().add(0, 5, 0);
        final Shulker shulker = world.spawn(safeSpawnLoc, Shulker.class, s -> {
            s.setAI(false);
            s.setSilent(true);
            s.setInvulnerable(true);
            s.setGravity(false);
            s.setPeek(0);
            s.setCollidable(true);
            s.setInvisible(true);
            final AttributeInstance scaleAttr = s.getAttribute(Attribute.SCALE);
            if (scaleAttr != null) {
                scaleAttr.setBaseValue(cellSize);
            }
            s.getPersistentDataContainer().set(
                    SHULKER_TYPE_KEY, PersistentDataType.STRING, "shulker");
            s.getPersistentDataContainer().set(
                    new NamespacedKey("sculpt", "path"),
                    PersistentDataType.STRING, leaf.path());
        });
        if (shulker == null) {
            seat.remove();
            return null;
        }

        // 3) Shulker rides the seat → teleports to cell position
        if (!seat.addPassenger(shulker)) {
            shulker.remove();
            seat.remove();
            return null;
        }

        if (!sculp.attachCollisionShulker(leaf.path(), shulker)) {
            remove(shulker);
            return null;
        }

        return shulker;
    }

    /**
     * Remove a shulker entity and its seat.
     *
     * @param shulker the shulker entity to remove
     */
    public static void remove(final Shulker shulker) {
        if (shulker == null) return;
        // Capture the seat before removing the passenger, then remove both.
        final var vehicle = shulker.getVehicle();
        if (shulker.isValid()) shulker.remove();
        if (vehicle != null && vehicle.isValid()) {
            vehicle.remove();
        }
    }

    /**
     * Check whether a Shulker entity belongs to the sculpt system.
     *
     * @param shulker the entity to check
     * @return true if it has the sculpt shulker PDC marker
     */
    public static boolean isSculptShulker(final Shulker shulker) {
        return "shulker".equals(shulker.getPersistentDataContainer()
            .get(SHULKER_TYPE_KEY, PersistentDataType.STRING));
    }
}
