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

public final class PreviewHighlight {

    private static final NamespacedKey HOVER_KEY = new NamespacedKey("sculpt", "hover");
    private final PrivateItemDisplay display;
    private UUID lastWorld;
    private int lastBlockX, lastBlockY, lastBlockZ;
    private int lastGrid, lastCellX, lastCellY, lastCellZ;
    private boolean shown;

    public PreviewHighlight(Plugin plugin) {
        this.display = new PrivateItemDisplay(plugin);
    }

    public void show(VirtualGridHit hit, int playerGrid, Player viewer) {
        org.bukkit.block.Block block = hit.block();
        // Cube 中心 = grid cell 在方塊體積內的中心（表面往內半個 grid）
        float cellSize = 1.0f / playerGrid;
        float cx = block.getX() + (hit.pgx() + 0.5f) * cellSize;
        float cy = block.getY() + (hit.pgy() + 0.5f) * cellSize;
        float cz = block.getZ() + (hit.pgz() + 0.5f) * cellSize;

        float cellWorldSize = 1.0f / playerGrid;
        final UUID worldId = block.getWorld().getUID();
        if (shown && worldId.equals(lastWorld)
                && block.getX() == lastBlockX
                && block.getY() == lastBlockY
                && block.getZ() == lastBlockZ
                && playerGrid == lastGrid
                && hit.pgx() == lastCellX
                && hit.pgy() == lastCellY
                && hit.pgz() == lastCellZ) {
            return;
        }
        shown = true;
        lastWorld = worldId;
        lastBlockX = block.getX();
        lastBlockY = block.getY();
        lastBlockZ = block.getZ();
        lastGrid = playerGrid;
        lastCellX = hit.pgx();
        lastCellY = hit.pgy();
        lastCellZ = hit.pgz();

        Location centre = new Location(block.getWorld(), cx, cy, cz);
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
