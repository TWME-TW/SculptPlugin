package dev.twme.sculpt.transport;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

/**
 * Per-chunk handle returned by {@link TransportSession#spawn}.
 */
public interface DisplayHandle {

    /** Replace the ItemStack the wrapped {@code ItemDisplay} is rendering. */
    void setItemStack(ItemStack head);

    /** Update the Transformation (scale, translation, rotation). */
    void setTransformation(Transformation transformation);

    /** Despawn the underlying entity. Idempotent. */
    void despawn();

    /** Hide or reveal this entity to a specific player. */
    void setVisible(Player viewer, boolean visible);

    /** UUID of the underlying entity. */
    UUID getEntityId();

    /** Location of the underlying entity. */
    Location getLocation();

    /** Whether the underlying display still exists and may be mutated. */
    boolean isValid();

    /** Write a string value to the PersistentDataContainer. */
    void setPDC(NamespacedKey key, String value);

    /** Read a string value from the PersistentDataContainer. */
    String getPDC(NamespacedKey key);

    /** Remove a legacy or no-longer-applicable PDC value. */
    default void removePDC(NamespacedKey key) {
        // Optional for non-Bukkit test and transport implementations.
    }

    /** Write a binary value to the PersistentDataContainer. */
    void setPDCBytes(NamespacedKey key, byte[] value);
}
