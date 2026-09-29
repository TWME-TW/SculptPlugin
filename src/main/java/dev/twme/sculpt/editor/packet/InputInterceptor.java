package dev.twme.sculpt.editor.packet;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;

/**
 * Reads the editor's key and right-click input from serverbound packets.
 *
 * <p>The client sees tool icons in its hotbar while the real slots may be
 * empty, and the server fires no Bukkit event for using or dropping an empty
 * hand. Using an item, dropping, and swapping hands are therefore taken
 * straight from the packets and cancelled, so real items are never used or
 * dropped either. Clicks on blocks still reach Bukkit.</p>
 *
 * <p>Callbacks run on the Netty thread; the handler must reschedule.</p>
 */
public final class InputInterceptor extends PacketListenerAbstract {

    /** Input the editor reacts to. */
    public enum Input {
        /** Right-click without a block under the cursor. */
        USE,
        /** {@code Q}. */
        DROP,
        /** {@code F}. */
        SWAP
    }

    private final Predicate<UUID> editing;
    private final BiConsumer<UUID, Input> handler;
    /**
     * Players whose last packet was a main-hand click on a block. The client
     * follows such a click with a use-item packet in the same frame; that
     * one belongs to the same physical click.
     */
    private final Map<UUID, Boolean> clickedBlock = new ConcurrentHashMap<>();
    private PacketListenerCommon registration;

    public InputInterceptor(final Predicate<UUID> editing, final BiConsumer<UUID, Input> handler) {
        super(PacketListenerPriority.LOW);
        this.editing = editing;
        this.handler = handler;
    }

    public void register() {
        registration = PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    public void unregister() {
        if (registration != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(registration);
            registration = null;
        }
        clickedBlock.clear();
    }

    public void forget(final UUID player) {
        clickedBlock.remove(player);
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        final UUID player = event.getUser().getUUID();
        if (player == null || !editing.test(player)) return;
        final PacketTypeCommon type = event.getPacketType();
        final boolean followsBlockClick = clickedBlock.remove(player) != null;

        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            if (new WrapperPlayClientPlayerBlockPlacement(event).getHand() == InteractionHand.MAIN_HAND) {
                clickedBlock.put(player, Boolean.TRUE);
            }
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            event.setCancelled(true);
            if (!followsBlockClick
                    && new WrapperPlayClientUseItem(event).getHand() == InteractionHand.MAIN_HAND) {
                handler.accept(player, Input.USE);
            }
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            final DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.DROP_ITEM || action == DiggingAction.DROP_ITEM_STACK) {
                event.setCancelled(true);
                handler.accept(player, Input.DROP);
            } else if (action == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
                event.setCancelled(true);
                handler.accept(player, Input.SWAP);
            }
        }
    }
}
