package dev.twme.sculpt.editor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.MessageUtil;
import dev.twme.sculpt.util.PrivateItemDisplay;
import dev.twme.sculpt.util.WandTool;

/**
 * Listener for the wand selection tool.
 *
 * <p>Handles left-click (pos1) and right-click (pos2) on blocks or
 * adaptive-collision entities in shulker mode. Completely independent of
 * the sculpt edit system.
 */
public final class WandListener implements Listener {

    private final Sculpt plugin;
    private final Map<UUID, RegionSelection> selections = new ConcurrentHashMap<>();
    private final Map<UUID, WandHighlight> highlights = new ConcurrentHashMap<>();

    public WandListener(final Sculpt plugin) {
        this.plugin = plugin;
    }

    // ========================================================================
    //  Events
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        final Player player = event.getPlayer();
        if (!WandTool.checkPlayer(player)) return;
        if (!player.hasPermission(SculptPermissions.USE_SELECTOR)) return;
        event.setCancelled(true);

        final Action action = event.getAction();

        // Left click on block = pos1
        if (action == Action.LEFT_CLICK_BLOCK) {
            final Block block = event.getClickedBlock();
            if (block == null) return;
            setPos1(player, block.getLocation());
            return;
        }

        // Right click on block = pos2
        if (action == Action.RIGHT_CLICK_BLOCK) {
            final Block block = event.getClickedBlock();
            if (block == null) return;
            setPos2(player, block.getLocation());
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!WandTool.checkPlayer(player)) return;
        if (!player.hasPermission(SculptPermissions.USE_SELECTOR)) return;

        final Location blockLoc = SculptClickTarget.blockLocation(event.getEntity());
        if (blockLoc == null) return;

        event.setCancelled(true);
        setPos1(player, blockLoc);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(final PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        final Player player = event.getPlayer();
        if (!WandTool.checkPlayer(player)) return;
        if (!player.hasPermission(SculptPermissions.USE_SELECTOR)) return;

        final Location blockLoc = SculptClickTarget.blockLocation(event.getRightClicked());
        if (blockLoc == null) return;

        event.setCancelled(true);
        setPos2(player, blockLoc);
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        cleanup(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        // Clear selection when changing worlds (different world = invalid)
        clearSelection(event.getPlayer());
    }

    // ========================================================================
    //  Selection management
    // ========================================================================

    /**
     * Get the current selection for a player, or null if none.
     */
    public RegionSelection getSelection(final Player player) {
        return selections.get(player.getUniqueId());
    }

    /**
     * Clear the selection for a player.
     */
    public void clearSelection(final Player player) {
        final UUID uuid = player.getUniqueId();
        selections.remove(uuid);
        final WandHighlight hl = highlights.remove(uuid);
        if (hl != null) hl.remove();
    }

    private void setPos1(final Player player, final Location loc) {
        final RegionSelection prev = selections.get(player.getUniqueId());
        final RegionSelection sel = new RegionSelection(loc.toBlockLocation(),
            prev != null ? prev.pos2() : null);
        selections.put(player.getUniqueId(), sel);
        updateHighlight(player, sel);
        if (sel.isValid()) {
            MessageUtil.sendTranslatedActionBar(player, "wandtool.pos1",
                sel.minX(), sel.minY(), sel.minZ());
            MessageUtil.sendTranslatedActionBar(player, "wandtool.volume",
                sel.maxX() - sel.minX() + 1,
                sel.maxY() - sel.minY() + 1,
                sel.maxZ() - sel.minZ() + 1,
                sel.volume());
        } else {
            MessageUtil.sendTranslatedActionBar(player, "wandtool.pos1",
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
    }

    private void setPos2(final Player player, final Location loc) {
        final RegionSelection prev = selections.get(player.getUniqueId());
        final RegionSelection sel = new RegionSelection(
            prev != null ? prev.pos1() : null, loc.toBlockLocation());
        selections.put(player.getUniqueId(), sel);
        updateHighlight(player, sel);
        if (sel.isValid()) {
            MessageUtil.sendTranslatedActionBar(player, "wandtool.pos2",
                sel.maxX(), sel.maxY(), sel.maxZ());
            MessageUtil.sendTranslatedActionBar(player, "wandtool.volume",
                sel.maxX() - sel.minX() + 1,
                sel.maxY() - sel.minY() + 1,
                sel.maxZ() - sel.minZ() + 1,
                sel.volume());
        } else {
            MessageUtil.sendTranslatedActionBar(player, "wandtool.pos2",
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
    }

    private void updateHighlight(final Player player, final RegionSelection sel) {
        final WandHighlight hl = highlights.computeIfAbsent(
            player.getUniqueId(), k -> new WandHighlight());
        if (sel.isValid()) {
            hl.show(player, sel);
        } else {
            hl.remove();
        }
    }

    private void cleanup(final Player player) {
        clearSelection(player);
    }

    // ========================================================================
    //  Visual highlight (single glass ItemDisplay scaled to selection size)
    // ========================================================================

    private final class WandHighlight {

        private final PrivateItemDisplay display = new PrivateItemDisplay(plugin);

        void show(final Player player, final RegionSelection sel) {
            remove();

            final org.bukkit.World world = sel.world();
            if (world == null) return;

            final double cx = (sel.minX() + sel.maxX() + 1) / 2.0;
            final double cy = (sel.minY() + sel.maxY() + 1) / 2.0;
            final double cz = (sel.minZ() + sel.maxZ() + 1) / 2.0;
            final Location center = new Location(world, cx, cy, cz);

            final float sx = sel.maxX() - sel.minX() + 1;
            final float sy = sel.maxY() - sel.minY() + 1;
            final float sz = sel.maxZ() - sel.minZ() + 1;

            display.show(center, player, d -> {
                d.setItemStack(new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS));
                d.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(),
                    new Vector3f(sx + 0.1f, sy + 0.1f, sz + 0.1f),
                    new Quaternionf()));
                d.setDisplayWidth(0);
                d.setDisplayHeight(0);
                d.setViewRange(100);
                d.setVisibleByDefault(false);
                d.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey("sculpt", "hover"),
                    org.bukkit.persistence.PersistentDataType.STRING, "glass");
            });
        }

        void remove() {
            display.clear();
        }
    }
}
