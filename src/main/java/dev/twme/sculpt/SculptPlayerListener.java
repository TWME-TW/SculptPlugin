package dev.twme.sculpt;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import dev.twme.sculpt.blueprint.BlueprintManager;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.lang.LanguageManager;

/** Synchronizes Sculpt state with player connections. */
public final class SculptPlayerListener implements Listener {

    private final Sculpt plugin;

    public SculptPlayerListener(final Sculpt plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        for (final SculptBlock block : plugin.getActiveBlocks()) {
            try {
                block.spawnFor(player);
            } catch (final RuntimeException ignored) {
                // One damaged block must not prevent the remaining displays from syncing.
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        final BlueprintManager blueprintManager = plugin.getBlueprintManager();
        if (blueprintManager != null) blueprintManager.clearSelection(player);

        final UUID playerId = player.getUniqueId();
        final LanguageManager languageManager = plugin.getLanguageManager();
        if (languageManager != null) languageManager.removePlayerLanguage(playerId);
        plugin.clearPlayerTransientState(playerId);
    }
}
