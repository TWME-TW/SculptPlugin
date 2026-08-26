package dev.twme.sculpt.editor;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.blueprint.BlueprintManager;
import dev.twme.sculpt.blueprint.BlueprintSelectorItem;
import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;
import dev.twme.sculpt.util.WandTool;

/** Contextual F/Q controls for Sculpt mode and the two explicit selector tools. */
public final class SculptControlsListener implements Listener {

    private static final long DOUBLE_TAP_WINDOW_NANOS =
        TimeUnit.MILLISECONDS.toNanos(300L);
    private static final long DOUBLE_TAP_DELAY_TICKS = 6L;
    private static final FillMode[] FILL_CYCLE = {
        FillMode.SHULKER, FillMode.BARRIER, FillMode.NONE
    };
    private static final SculptDisplayMode[] DISPLAY_CYCLE = {
        SculptDisplayMode.AUTO, SculptDisplayMode.HEAD,
        SculptDisplayMode.TEXT_DISPLAY
    };

    private final Sculpt plugin;
    private final DoubleTapTracker<SwapContext> swapTaps =
        new DoubleTapTracker<>(DOUBLE_TAP_WINDOW_NANOS);
    private final DoubleTapTracker<DropContext> dropTaps =
        new DoubleTapTracker<>(DOUBLE_TAP_WINDOW_NANOS);

    public SculptControlsListener(final Sculpt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwapHands(final PlayerSwapHandItemsEvent event) {
        final Player player = event.getPlayer();
        // Bukkit exposes the pre-swap main-hand item as the item that would be
        // moved into the off hand. Use the event snapshot so tool dispatch is
        // stable even if an implementation has already prepared the swap.
        final ItemStack mainHand = event.getOffHandItem();

        if (WandTool.isWandTool(mainHand)) {
            if (!player.hasPermission(SculptPermissions.USE_SELECTOR)) return;
            event.setCancelled(true);
            final WandListener wand = plugin.getWandListener();
            if (wand != null) wand.clearSelection(player);
            MessageUtil.sendTranslatedActionBar(player, "wandtool.cleared");
            return;
        }

        final BlueprintManager blueprints = plugin.getBlueprintManager();
        if (BlueprintSelectorItem.isSelectorTool(mainHand)
                && blueprints != null && blueprints.isEnabled()) {
            event.setCancelled(true);
            registerTap(
                swapTaps, player, SwapContext.BLUEPRINT,
                () -> {
                    blueprints.clearSelection(player);
                    MessageUtil.sendTranslatedActionBar(
                        player, "command.sculpt.blueprint.select.cleared");
                },
                () -> {
                    final BlueprintManager.SelectionMode mode =
                        blueprints.toggleSelectionMode(player);
                    MessageUtil.sendTranslatedActionBar(player,
                        mode == BlueprintManager.SelectionMode.CUBOID
                            ? "command.sculpt.blueprint.select.mode_cuboid"
                            : "command.sculpt.blueprint.select.mode_single");
                });
            return;
        }

        // Bound blueprint items remain explicit content controls rather than
        // inheriting Sculpt mode's keyboard shortcuts.
        if (BlueprintSelectorItem.isBoundItem(mainHand)) return;
        if (!plugin.isSculptMode(player)) return;

        event.setCancelled(true);
        final SwapContext context = plugin.isSculptModeSuspended(player)
            ? SwapContext.SCULPT_SUSPENDED : SwapContext.SCULPT_ACTIVE;
        registerTap(
            swapTaps, player, context,
            () -> handleSingleSculptSwap(player, context),
            () -> toggleSculptSuspension(player));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        final Player player = event.getPlayer();
        final ItemStack dropped = event.getItemDrop().getItemStack();

        // Q is unmodified for explicit tools and blueprint items.
        if (WandTool.isWandTool(dropped)
                || BlueprintSelectorItem.isSelectorTool(dropped)
                || BlueprintSelectorItem.isBoundItem(dropped)) {
            return;
        }
        if (!plugin.isSculptModeActive(player)) return;

        event.setCancelled(true);
        registerTap(
            dropTaps, player, DropContext.SCULPT_ACTIVE,
            () -> cycleFill(player),
            () -> cycleDisplay(player));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        clearPending(event.getPlayer());
    }

    public void clearPending(final Player player) {
        final UUID playerId = player.getUniqueId();
        cancel(swapTaps.clear(playerId));
        cancel(dropTaps.clear(playerId));
    }

    public void shutdown() {
        swapTaps.clearAll().forEach(SculptControlsListener::cancel);
        dropTaps.clearAll().forEach(SculptControlsListener::cancel);
    }

    private void handleSingleSculptSwap(
            final Player player,
            final SwapContext originalContext) {
        if (!player.isOnline() || !plugin.isSculptMode(player)) return;
        if (originalContext == SwapContext.SCULPT_SUSPENDED
                || plugin.isSculptModeSuspended(player)) {
            MessageUtil.sendTranslatedActionBar(
                player, "sculptcontrols.paused_reminder");
            return;
        }

        final int previous = plugin.gridSizeFor(player);
        final int next = plugin.cycleGridSize(player);
        if (next == previous) {
            MessageUtil.sendTranslatedActionBar(
                player, "command.grid_cycle.no_others", next);
        } else {
            MessageUtil.sendTranslatedActionBar(
                player, "command.grid_cycle.cycled", previous, next);
        }
    }

    private void toggleSculptSuspension(final Player player) {
        if (!player.isOnline() || !plugin.isSculptMode(player)) return;
        cancel(dropTaps.clear(player.getUniqueId()));
        final boolean suspended = plugin.toggleSculptModeSuspended(player);
        MessageUtil.sendTranslatedActionBar(player,
            suspended ? "sculptcontrols.paused" : "sculptcontrols.resumed");
    }

    private void cycleFill(final Player player) {
        if (!player.isOnline() || !plugin.isSculptModeActive(player)) return;
        final FillMode next = nextAllowedFillMode(
            plugin.fillModeFor(player), player::hasPermission);
        if (next == null) {
            MessageUtil.sendTranslatedActionBar(player, "general.no_permission");
            return;
        }
        plugin.setFillMode(player, next);
        MessageUtil.sendTranslatedActionBar(player, "sculptfill." + next.id());
    }

    private void cycleDisplay(final Player player) {
        if (!player.isOnline() || !plugin.isSculptModeActive(player)) return;
        final SculptDisplayMode next = nextAllowedDisplayMode(
            plugin.displayModeFor(player), player::hasPermission);
        if (next == null) {
            MessageUtil.sendTranslatedActionBar(player, "general.no_permission");
            return;
        }
        plugin.setDisplayMode(player, next);
        MessageUtil.sendTranslatedActionBar(player, "sculptdisplay." + next.id());
    }

    private <C> void registerTap(
            final DoubleTapTracker<C> tracker,
            final Player player,
            final C context,
            final Runnable singleAction,
            final Runnable doubleAction) {
        final DoubleTapTracker.Registration<C> registration = tracker.register(
            player.getUniqueId(), context, System.nanoTime(), singleAction);
        final DoubleTapTracker.PendingTap<C> previous = registration.previous();
        if (previous != null) {
            cancel(previous);
            if (!registration.doubleTap()) previous.singleAction().run();
        }
        if (registration.doubleTap()) {
            doubleAction.run();
            return;
        }

        final DoubleTapTracker.PendingTap<C> pending = registration.pending();
        final Object task = FoliaScheduler.runEntityTaskLater(
            plugin, player, () -> {
                if (tracker.expire(pending) && player.isOnline()) {
                    pending.singleAction().run();
                }
            }, DOUBLE_TAP_DELAY_TICKS);
        pending.taskHandle(task);
    }

    private static void cancel(final DoubleTapTracker.PendingTap<?> tap) {
        if (tap != null) FoliaScheduler.cancelTask(tap.taskHandle());
    }

    static FillMode nextAllowedFillMode(
            final FillMode current,
            final Predicate<String> permissionChecker) {
        return nextAllowed(
            current, FILL_CYCLE,
            mode -> permissionChecker.test(SculptPermissions.fill(mode.id())));
    }

    static SculptDisplayMode nextAllowedDisplayMode(
            final SculptDisplayMode current,
            final Predicate<String> permissionChecker) {
        return nextAllowed(
            current, DISPLAY_CYCLE,
            mode -> permissionChecker.test(SculptPermissions.display(mode.id())));
    }

    private static <T> T nextAllowed(
            final T current,
            final T[] cycle,
            final Predicate<T> allowed) {
        int currentIndex = -1;
        for (int index = 0; index < cycle.length; index++) {
            if (cycle[index] == current) {
                currentIndex = index;
                break;
            }
        }
        for (int offset = 1; offset <= cycle.length; offset++) {
            final T candidate = cycle[(currentIndex + offset) % cycle.length];
            if (allowed.test(candidate)) return candidate;
        }
        return null;
    }

    private enum SwapContext {
        SCULPT_ACTIVE,
        SCULPT_SUSPENDED,
        BLUEPRINT
    }

    private enum DropContext {
        SCULPT_ACTIVE
    }
}
