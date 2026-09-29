package dev.twme.sculpt.editor.packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;

import io.github.retrooper.packetevents.util.SpigotConversionUtil;

/**
 * Shows the editor's tool palette in place of a player's hotbar without
 * touching the real inventory. Every outbound player-inventory update is
 * rewritten while the overlay is active, so server-side changes to the
 * hotbar can never replace the tools on the client.
 */
public final class HotbarOverlay extends PacketListenerAbstract {

    /** Player inventory container id and the protocol slots of the hotbar. */
    private static final int PLAYER_WINDOW = 0;
    private static final int FIRST_HOTBAR_SLOT = 36;
    private static final int HOTBAR_SIZE = 9;

    private final Map<UUID, List<ItemStack>> overlays = new ConcurrentHashMap<>();
    private PacketListenerCommon registration;

    public HotbarOverlay() {
        super(PacketListenerPriority.HIGH);
    }

    public void register() {
        registration = PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    public void unregister() {
        if (registration != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registration);
            registration = null;
        }
        overlays.clear();
    }

    /** Replace the visible hotbar with {@code icons} (nine items) and resend the inventory. */
    public void show(final Player player, final List<org.bukkit.inventory.ItemStack> icons) {
        final List<ItemStack> converted = new ArrayList<>(HOTBAR_SIZE);
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            final org.bukkit.inventory.ItemStack icon = slot < icons.size() ? icons.get(slot) : null;
            converted.add(icon == null ? ItemStack.EMPTY : SpigotConversionUtil.fromBukkitItemStack(icon));
        }
        overlays.put(player.getUniqueId(), List.copyOf(converted));
        player.updateInventory();
    }

    /** Restore the real hotbar. */
    public void hide(final Player player) {
        if (overlays.remove(player.getUniqueId()) != null && player.isOnline()) {
            player.updateInventory();
        }
    }

    public void forget(final UUID player) {
        overlays.remove(player);
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        final UUID player = event.getUser().getUUID();
        if (player == null) return;
        final List<ItemStack> icons = overlays.get(player);
        if (icons == null) return;

        if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            final WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            if (packet.getWindowId() != PLAYER_WINDOW) return;
            final List<ItemStack> items = new ArrayList<>(packet.getItems());
            for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
                final int index = FIRST_HOTBAR_SLOT + slot;
                if (index < items.size()) items.set(index, icons.get(slot));
            }
            packet.setItems(items);
            event.markForReEncode(true);
        } else if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            final WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            final int slot = packet.getSlot() - FIRST_HOTBAR_SLOT;
            if (packet.getWindowId() != PLAYER_WINDOW || slot < 0 || slot >= HOTBAR_SIZE) return;
            packet.setItem(icons.get(slot));
            event.markForReEncode(true);
        } else if (event.getPacketType() == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            final WrapperPlayServerSetPlayerInventory packet = new WrapperPlayServerSetPlayerInventory(event);
            final int slot = packet.getSlot();
            if (slot < 0 || slot >= HOTBAR_SIZE) return;
            packet.setStack(icons.get(slot));
            event.markForReEncode(true);
        }
    }
}
