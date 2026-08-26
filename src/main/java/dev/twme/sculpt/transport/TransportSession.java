package dev.twme.sculpt.transport;

import java.util.Map;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

/**
 * Owns all chunk displays for one SculptBlock lifetime.
 *
 * <p>v2: added spawnRoot, spawnRiding, removePassenger, getPassengerMap
 * for the new vehicle-passenger entity hierarchy.
 */
public interface TransportSession {

    /** Spawn a chunk display with identity transformation. */
    default DisplayHandle spawn(Location loc, ItemStack head) {
        return spawn(loc, head, new Transformation(
            new org.joml.Vector3f(), new org.joml.Quaternionf(),
            new org.joml.Vector3f(1, 1, 1), new org.joml.Quaternionf()));
    }

    /** Spawn a chunk display with the given transformation. */
    DisplayHandle spawn(Location loc, ItemStack head, Transformation transform);

    /** Spawn the root entity (invisible vehicle) at the block centre. */
    DisplayHandle spawnRoot(Location blockCenter);

    /**
     * Spawn a chunk display and mount it as a passenger on {@code vehicle}.
     */
    DisplayHandle spawnRiding(DisplayHandle vehicle, Location loc,
                               ItemStack head, Transformation transform);

    /** Remove a passenger from its vehicle. Does NOT despawn the child. */
    void removePassenger(DisplayHandle vehicle, DisplayHandle child);

    /** Despawn a single handle. */
    void destroy(DisplayHandle handle);

    /**
     * Adopt a handle that was discovered on an existing entity during
     * reconstruction. Implementations that do not maintain a handle registry
     * may ignore this call.
     */
    default void track(DisplayHandle handle) {}

    /** Despawn all handles (root + all passengers). */
    void destroyAll();

    /** Reveal or hide all handles to/from a player. */
    void setVisible(Player viewer, boolean visible);

    /** The root entity, or null. */
    DisplayHandle getRootEntity();

    /**
     * Map of sculpt:path → DisplayHandle for all current passengers.
     * Used during ChunkLoad reconstruction.
     */
    Map<String, DisplayHandle> getPassengerMap();
}
