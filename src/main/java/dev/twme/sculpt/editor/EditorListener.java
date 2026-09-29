package dev.twme.sculpt.editor;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import dev.twme.sculpt.util.FoliaScheduler;
import io.papermc.paper.event.player.PlayerPickBlockEvent;

/**
 * Translates vanilla input into editor intents while a player is editing:
 * clicks, scroll, and middle click. Everything else
 * that would change the world or the (hidden) real hotbar is cancelled.
 * Outside the editor, only the Sculpt Knife is handled.
 */
public final class EditorListener implements Listener {

    private static final int HOTBAR_SIZE = 9;

    private final EditorService service;

    public EditorListener(final EditorService service) {
        this.service = service;
    }

    // =====================================================================
    //  Clicks
    // =====================================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        final Player player = event.getPlayer();
        final EditorSession session = service.session(player);
        if (session == null) {
            if (event.getHand() == EquipmentSlot.HAND
                    && isRightClick(event.getAction())
                    && EditorService.isKnife(event.getItem())) {
                event.setCancelled(true);
                service.enter(player);
            }
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        final Action action = event.getAction();
        if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
            service.click(player, false);
        } else if (isRightClick(action)) {
            service.click(player, true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        final EditorSession session = service.session(event.getPlayer());
        if (session == null) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) service.click(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(final EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!service.isEditing(player)) return;
        event.setCancelled(true);
        service.click(player, false);
    }

    private static boolean isRightClick(final Action action) {
        return action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR;
    }

    // =====================================================================
    //  Keys
    // =====================================================================

    /** Scroll or number keys select a tool; with {@code Shift} they resize it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(final PlayerItemHeldEvent event) {
        final Player player = event.getPlayer();
        final EditorSession session = service.session(player);
        if (session == null) return;
        if (player.isSneaking()) {
            event.setCancelled(true);
            session.adjust(scrollDirection(event.getPreviousSlot(), event.getNewSlot()));
            return;
        }
        session.selectTool(ToolId.ofSlot(event.getNewSlot()));
    }

    /**
     * The direction of a hotbar change: scrolling down (towards higher slots)
     * grows, scrolling up shrinks. Wrapping from the last to the first slot
     * counts as one step down.
     */
    static int scrollDirection(final int previous, final int next) {
        final int forward = Math.floorMod(next - previous, HOTBAR_SIZE);
        if (forward == 0) return 0;
        return forward <= HOTBAR_SIZE / 2 ? 1 : -1;
    }

    /**
     * {@code F} and {@code Q} are read from packets by the
     * {@link dev.twme.sculpt.editor.packet.InputInterceptor}, because the
     * real slot may be empty; these events only guard the real items.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(final PlayerSwapHandItemsEvent event) {
        if (service.isEditing(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(final PlayerDropItemEvent event) {
        if (service.isEditing(event.getPlayer())) event.setCancelled(true);
    }

    /** Middle click: pick the material under the cursor. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPick(final PlayerPickBlockEvent event) {
        final EditorSession session = service.session(event.getPlayer());
        if (session == null) return;
        event.setCancelled(true);
        session.pickMaterialAtCursor();
    }

    // =====================================================================
    //  Protection of the hidden inventory and the world
    // =====================================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(final InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && service.isEditing(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && service.isEditing(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(final InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && service.isEditing(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(final BlockBreakEvent event) {
        if (service.isEditing(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(final BlockPlaceEvent event) {
        if (service.isEditing(event.getPlayer())) event.setCancelled(true);
    }

    // =====================================================================
    //  Lifecycle
    // =====================================================================

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        service.forget(event.getPlayer());
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        service.exit(event.getPlayer(), true);
    }

    @EventHandler
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        service.exit(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(final PlayerGameModeChangeEvent event) {
        final Player player = event.getPlayer();
        if (!service.isEditing(player)) return;
        // The client rebuilds its inventory view after a game mode change.
        FoliaScheduler.runEntityTask(service.plugin(), player,
            () -> service.refreshHotbar(player));
    }
}
