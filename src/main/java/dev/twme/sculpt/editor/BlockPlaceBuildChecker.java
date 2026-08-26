package dev.twme.sculpt.editor;

import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import dev.twme.sculpt.plugin.SculptPermissions;

/**
 * Probes region-protection plugins with a synthetic {@link BlockPlaceEvent}.
 * The probe event is always cancelled after higher-priority protection
 * handlers have had a chance to reject the edit.
 *
 * @see <a href="https://github.com/TWME-TW/DebugStickPro/blob/main/src/main/java/dev/twme/debugstickpro/listeners/BlockPlaceEventListenerCanBuildChecker.java">DebugStickPro checker</a>
 */
public final class BlockPlaceBuildChecker implements Listener {

    private final ThreadLocal<Probe> activeProbe = new ThreadLocal<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(final BlockPlaceEvent event) {
        final Probe probe = activeProbe.get();
        if (probe == null || !probe.matches(event)) return;

        probe.capture(event.isCancelled(), event.canBuild());
        event.setBuild(false);
        event.setCancelled(true);
    }

    public boolean canBuild(final Player player, final Block block) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(block, "block");
        if (hasBypassPermission(player)) return true;
        return probeCanBuild(player, block,
            player.getInventory().getItemInMainHand());
    }

    public boolean canBuild(final Player player, final Block block,
                            final ItemStack itemInHand) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(itemInHand, "itemInHand");
        if (hasBypassPermission(player)) return true;

        return probeCanBuild(player, block, itemInHand);
    }

    private boolean probeCanBuild(final Player player, final Block block,
                                  final ItemStack itemInHand) {
        final BlockPlaceEvent event = new BlockPlaceEvent(
            block,
            block.getState(),
            block,
            itemInHand.clone(),
            player,
            true,
            EquipmentSlot.HAND);
        final Probe probe = new Probe(event);
        final Probe previous = activeProbe.get();
        activeProbe.set(probe);
        try {
            Bukkit.getPluginManager().callEvent(event);
            return probe.result();
        } finally {
            if (previous == null) {
                activeProbe.remove();
            } else {
                activeProbe.set(previous);
            }
        }
    }

    static boolean hasBypassPermission(final Player player) {
        return player.hasPermission(SculptPermissions.BYPASS_REGION_PROTECTION);
    }

    static final class Probe {
        private final Object event;
        private boolean observed;
        private boolean allowed;

        Probe(final Object event) {
            this.event = event;
        }

        boolean matches(final Object candidate) {
            return event == candidate;
        }

        void capture(final boolean cancelled, final boolean canBuild) {
            observed = true;
            allowed = !cancelled && canBuild;
        }

        boolean result() {
            return observed && allowed;
        }
    }
}
