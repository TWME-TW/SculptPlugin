package dev.twme.sculpt.plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.editor.RegionSelection;
import dev.twme.sculpt.render.TextLightingRefreshResult;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

/** Clears TextDisplay brightness overrides in the current wand selection. */
public final class SculptRelightCommand {

    /** Bound one Paper tick even when a selection spans many SculptBlocks. */
    static final int MAX_BLOCKS_PER_SLICE = 8;

    private final Sculpt plugin;
    private final Set<UUID> activeOperations =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SculptRelightCommand(final Sculpt plugin) {
        this.plugin = plugin;
    }

    public boolean execute(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendTranslated(sender,
                "command.sculpt.relight.player_only");
            return true;
        }
        if (!sender.hasPermission(SculptPermissions.RELIGHT)) {
            MessageUtil.sendTranslated(sender, "general.no_permission");
            MessageUtil.sendTranslated(sender, "general.required_perm",
                SculptPermissions.RELIGHT);
            return true;
        }
        if (args.length != 0) {
            MessageUtil.sendTranslated(sender, "command.sculpt.relight.usage");
            return true;
        }

        final RegionSelection selection = plugin.getWandListener() == null
            ? null : plugin.getWandListener().getSelection(player);
        if (selection == null || !selection.isValid()) {
            MessageUtil.sendTranslated(sender,
                "command.sculpt.relight.no_selection");
            return true;
        }

        final List<SculptBlock> selected = new ArrayList<>();
        for (final SculptBlock block : plugin.getActiveBlocks()) {
            if (block.world.equals(selection.world())
                    && selection.contains(block.pos)) {
                selected.add(block);
            }
        }
        selected.sort(Comparator
            .comparingInt((SculptBlock block) -> block.pos.getBlockX() >> 4)
            .thenComparingInt(block -> block.pos.getBlockZ() >> 4)
            .thenComparingInt(block -> block.pos.getBlockY())
            .thenComparingInt(block -> block.pos.getBlockX())
            .thenComparingInt(block -> block.pos.getBlockZ()));
        if (selected.isEmpty()) {
            MessageUtil.sendTranslated(sender,
                "command.sculpt.relight.no_blocks");
            return true;
        }
        if (!activeOperations.add(player.getUniqueId())) {
            MessageUtil.sendTranslated(sender,
                "command.sculpt.relight.already_running");
            return true;
        }

        MessageUtil.sendTranslated(sender, "command.sculpt.relight.started",
            selected.size());
        new RelightOperation(player, selected).scheduleNext();
        return true;
    }

    private final class RelightOperation {
        private final Player player;
        private final ArrayDeque<SculptBlock> pending;
        private final LongAdder blocksChecked = new LongAdder();
        private final LongAdder displaysChecked = new LongAdder();
        private final LongAdder displaysUpdated = new LongAdder();
        private final LongAdder failedBlocks = new LongAdder();
        private final AtomicReference<Throwable> firstFailure =
            new AtomicReference<>();
        private final AtomicBoolean finished = new AtomicBoolean();

        private RelightOperation(
                final Player player,
                final List<SculptBlock> selected) {
            this.player = player;
            this.pending = new ArrayDeque<>(selected);
        }

        private void scheduleNext() {
            while (true) {
                if (plugin.isDisabling() || !player.isOnline()) {
                    finish();
                    return;
                }
                final SculptBlock next = pending.peekFirst();
                if (next == null) {
                    finish();
                    return;
                }
                try {
                    FoliaScheduler.runRegionTaskLater(
                        plugin, next.pos, this::runSlice, 1L);
                    return;
                } catch (final RuntimeException schedulingFailure) {
                    pending.removeFirst();
                    recordFailure(schedulingFailure);
                }
            }
        }

        private void runSlice() {
            final SculptBlock owner = pending.peekFirst();
            if (owner == null) {
                finish();
                return;
            }
            final int chunkX = owner.pos.getBlockX() >> 4;
            final int chunkZ = owner.pos.getBlockZ() >> 4;
            int processed = 0;
            while (processed < MAX_BLOCKS_PER_SLICE) {
                final SculptBlock block = pending.peekFirst();
                if (block == null
                        || block.pos.getBlockX() >> 4 != chunkX
                        || block.pos.getBlockZ() >> 4 != chunkZ) {
                    break;
                }
                pending.removeFirst();
                processed++;
                try {
                    if (plugin.getActiveBlock(BlockPosKey.of(block.pos)) != block) {
                        continue;
                    }
                    blocksChecked.increment();
                    final TextLightingRefreshResult result =
                        block.refreshTextDisplayLighting();
                    displaysChecked.add(result.displaysChecked());
                    displaysUpdated.add(result.displaysUpdated());
                } catch (final RuntimeException failure) {
                    recordFailure(failure);
                }
            }
            scheduleNext();
        }

        private void recordFailure(final RuntimeException failure) {
            failedBlocks.increment();
            firstFailure.compareAndSet(null, failure);
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) return;
            activeOperations.remove(player.getUniqueId());

            final Throwable failure = firstFailure.get();
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING,
                    "[Sculpt] TextDisplay selection relight failed for "
                        + failedBlocks.sum()
                        + " SculptBlock(s); showing the first error",
                    failure);
            }
            if (plugin.isDisabling() || !player.isOnline()) return;
            FoliaScheduler.runEntityTask(plugin, player, () -> {
                if (!player.isOnline()) return;
                MessageUtil.sendTranslated(player,
                    "command.sculpt.relight.completed",
                    blocksChecked.sum(), displaysChecked.sum(),
                    displaysUpdated.sum());
                if (failedBlocks.sum() > 0) {
                    MessageUtil.sendTranslated(player,
                        "command.sculpt.relight.failed", failedBlocks.sum());
                }
            });
        }
    }
}
