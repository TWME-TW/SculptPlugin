package dev.twme.sculpt.editor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.blueprint.BlueprintData;
import dev.twme.sculpt.blueprint.BlueprintManager;
import dev.twme.sculpt.blueprint.BlueprintSelectorItem;
import dev.twme.sculpt.blueprint.PasteSettings;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Right-clicking with an item bound to a blueprint pastes it in front of the
 * clicked face. Pastes are recorded for {@code /sculpt undo}.
 */
public final class BlueprintItemListener implements Listener {

    private final Sculpt plugin;
    private final Map<UUID, Integer> lastPaste = new ConcurrentHashMap<>();

    public BlueprintItemListener(final Sculpt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        final Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;
        final ItemStack item = event.getItem();
        if (!BlueprintSelectorItem.isBoundItem(item) || !enabled()) return;
        event.setCancelled(true);
        final Player player = event.getPlayer();
        if (!claim(player)) return;
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            final Block clicked = event.getClickedBlock();
            paste(player, item, clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0.5, 0.5),
                event.getBlockFace());
            return;
        }
        final Block target = player.getTargetBlockExact(10);
        if (target != null && target.getType().isSolid()) {
            paste(player, item, target.getLocation().add(0.5, 1, 0.5), null);
        } else {
            paste(player, item, player.getLocation(), null);
        }
    }

    /** Right clicks on AIR-backed SculptBlocks arrive through their Interaction proxies. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        final Player player = event.getPlayer();
        final ItemStack item = player.getInventory().getItemInMainHand();
        if (!BlueprintSelectorItem.isBoundItem(item) || !enabled()) return;
        final Location block = SculptClickTarget.blockLocation(event.getRightClicked());
        if (block == null) return;
        event.setCancelled(true);
        if (!claim(player)) return;
        final BlockFace face = clickedFace(player, block);
        paste(player, item, block.getBlock().getRelative(face).getLocation().add(0.5, 0.5, 0.5), face);
    }

    private void paste(final Player player, final ItemStack item, final Location target, final BlockFace face) {
        final UUID blueprintId = BlueprintSelectorItem.getBlueprintId(item);
        if (blueprintId == null) return;
        final BlueprintManager manager = plugin.getBlueprintManager();
        final BlueprintData data = load(manager, player, blueprintId);
        if (data == null) {
            MessageUtil.sendTranslated(player, "command.sculpt.blueprint.paste.not_found", blueprintId.toString());
            return;
        }
        final PasteSettings settings = BlueprintSelectorItem.getPasteSettings(
            item, manager.getPlayerSettings(player.getUniqueId()));
        final int[] bounds = manager.pasteEngine().previewBounds(player, data, target, settings, face);
        final List<Block> blocks = new ArrayList<>();
        for (int x = 0; x < bounds[3]; x++) {
            for (int y = 0; y < bounds[4]; y++) {
                for (int z = 0; z < bounds[5]; z++) {
                    blocks.add(player.getWorld().getBlockAt(bounds[0] + x, bounds[1] + y, bounds[2] + z));
                }
            }
        }
        final String[] error = new String[1];
        plugin.getBuildEngine().recordChange(player, "blueprint", blocks,
            () -> error[0] = manager.pasteBlueprint(player, data, target, settings, face));
        if (error[0] != null) {
            MessageUtil.sendTranslated(player, error[0]);
            return;
        }
        MessageUtil.sendTranslated(player, "command.sculpt.blueprint.paste.success", data.name());
        if (plugin.getConfig().getBoolean("blueprint.consumeItemAfterPaste", false)
                && player.getGameMode() != GameMode.CREATIVE) {
            item.subtract(1);
        }
    }

    private boolean enabled() {
        final BlueprintManager manager = plugin.getBlueprintManager();
        return manager != null && manager.isEnabled();
    }

    /** Bukkit can report one right click as both a block and an air interaction. */
    private boolean claim(final Player player) {
        final int tick = Bukkit.getCurrentTick();
        final Integer previous = lastPaste.put(player.getUniqueId(), tick);
        return previous == null || previous != tick;
    }

    private static BlueprintData load(final BlueprintManager manager, final Player player, final UUID id) {
        try {
            final BlueprintData data = manager.io().readBlueprint(player.getUniqueId(), id, false);
            return data != null ? data : manager.io().readBlueprint(player.getUniqueId(), id, true);
        } catch (final IOException failure) {
            return null;
        }
    }

    static BlockFace clickedFace(final Player player, final Location block) {
        final Location eye = player.getEyeLocation();
        final Vector origin = eye.toVector();
        final BoundingBox bounds = new BoundingBox(block.getBlockX(), block.getBlockY(), block.getBlockZ(),
            block.getBlockX() + 1.0, block.getBlockY() + 1.0, block.getBlockZ() + 1.0);
        final RayTraceResult hit = bounds.rayTrace(origin, eye.getDirection(), 10.0);
        if (hit != null && hit.getHitBlockFace() != null) return hit.getHitBlockFace();
        final Vector toward = origin.subtract(bounds.getCenter());
        if (Math.abs(toward.getY()) >= Math.abs(toward.getX()) && Math.abs(toward.getY()) >= Math.abs(toward.getZ())) {
            return toward.getY() >= 0 ? BlockFace.UP : BlockFace.DOWN;
        }
        if (Math.abs(toward.getX()) >= Math.abs(toward.getZ())) {
            return toward.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return toward.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }
}
