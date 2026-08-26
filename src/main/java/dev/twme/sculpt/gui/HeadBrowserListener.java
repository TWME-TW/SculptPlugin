package dev.twme.sculpt.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Routes inventory events and player cleanup to the head browser UI. */
public final class HeadBrowserListener implements Listener {

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(final InventoryClickEvent event) {
        HeadBrowserGUI.handleClick(event);
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        HeadBrowserGUI.clearPendingSearch(event.getPlayer().getUniqueId());
    }
}
