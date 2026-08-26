package dev.twme.sculpt.transport.bukkit;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

import dev.twme.sculpt.transport.DisplayHandle;

/**
 * Thin wrapper around a Bukkit {@code ItemDisplay}.
 */
public final class BukkitDisplayHandle implements DisplayHandle {

    private final ItemDisplay display;

    public BukkitDisplayHandle(ItemDisplay display) {
        this.display = display;
    }

    @Override
    public void setItemStack(ItemStack head) {
        display.setItemStack(head);
    }

    @Override
    public void setTransformation(Transformation transformation) {
        display.setTransformation(transformation);
    }

    @Override
    public void despawn() {
        if (!display.isDead()) {
            display.remove();
        }
    }

    @Override
    public void setVisible(Player viewer, boolean visible) {
        if (visible) {
            viewer.showEntity(plugin(), display);
        } else {
            viewer.hideEntity(plugin(), display);
        }
    }

    @Override
    public UUID getEntityId() {
        return display.getUniqueId();
    }

    @Override
    public Location getLocation() {
        return display.getLocation();
    }

    @Override
    public boolean isValid() {
        return display.isValid();
    }

    @Override
    public void setPDC(NamespacedKey key, String value) {
        display.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
    }

    @Override
    public String getPDC(NamespacedKey key) {
        return display.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    @Override
    public void removePDC(final NamespacedKey key) {
        display.getPersistentDataContainer().remove(key);
    }

    @Override
    public void setPDCBytes(NamespacedKey key, byte[] value) {
        display.getPersistentDataContainer().set(
            key, PersistentDataType.BYTE_ARRAY, value.clone());
    }

    // --- helpers used only by BukkitTransportSession / fill conversion -------

    public ItemDisplay entity() { return display; }

    void showFor(Player viewer, Plugin plugin) {
        viewer.showEntity(plugin, display);
    }

    void hideFor(Player viewer, Plugin plugin) {
        viewer.hideEntity(plugin, display);
    }

    private static Plugin plugin() {
        return org.bukkit.Bukkit.getPluginManager().getPlugin("Sculpt");
    }
}
