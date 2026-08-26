package dev.twme.sculpt.editor;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.twme.sculpt.util.PrivateItemDisplay;

public final class SelectionHighlight {

    private static final NamespacedKey HOVER_KEY = new NamespacedKey("sculpt", "hover");
    private final PrivateItemDisplay display;
    private UUID lastWorld;
    private int lastBlockX, lastBlockY, lastBlockZ;
    private int lastCellX, lastCellY, lastCellZ;
    private boolean shown;

    public SelectionHighlight(Plugin plugin) {
        this.display = new PrivateItemDisplay(plugin);
    }

    public void show(VirtualGridHit hit, int playerGrid, Player viewer) {
        int side = hit.grid16Side(playerGrid);
        int minX = hit.grid16MinX(playerGrid);
        int minY = hit.grid16MinY(playerGrid);
        int minZ = hit.grid16MinZ(playerGrid);
        org.bukkit.block.Block block = hit.block();

        final UUID worldId = block.getWorld().getUID();
        if (shown && worldId.equals(lastWorld)
                && block.getX() == lastBlockX
                && block.getY() == lastBlockY
                && block.getZ() == lastBlockZ
                && minX == lastCellX && minY == lastCellY && minZ == lastCellZ) {
            return;
        }
        shown = true;
        lastWorld = worldId;
        lastBlockX = block.getX();
        lastBlockY = block.getY();
        lastBlockZ = block.getZ();
        lastCellX = minX;
        lastCellY = minY;
        lastCellZ = minZ;

        float cellWorldSize = (float) side / 16f;
        float centerX = (float) block.getX() + ((float) minX / 16f) + cellWorldSize / 2f;
        float centerY = (float) block.getY() + ((float) minY / 16f) + cellWorldSize / 2f;
        float centerZ = (float) block.getZ() + ((float) minZ / 16f) + cellWorldSize / 2f;

        Location centre = new Location(block.getWorld(), centerX, centerY, centerZ);
        float highlightScale = cellWorldSize * 1.1f;
        Transformation tx = new Transformation(
            new Vector3f(), new Quaternionf(),
            new Vector3f(highlightScale, highlightScale, highlightScale),
            new Quaternionf());

        display.show(centre, viewer, d -> {
            d.setItemStack(new ItemStack(Material.GLASS));
            d.setTransformation(tx);
            d.setDisplayWidth(0);
            d.setDisplayHeight(0);
            d.setViewRange(100);
            d.setVisibleByDefault(false);
            d.getPersistentDataContainer().set(
                HOVER_KEY,
                org.bukkit.persistence.PersistentDataType.STRING, "glass");
        });
    }

    public void clear() {
        if (!shown) return;
        display.clear();
        shown = false;
        lastWorld = null;
    }
}
