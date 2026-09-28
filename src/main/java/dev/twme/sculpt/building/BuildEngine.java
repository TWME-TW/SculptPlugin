package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.util.BlockEditSounds;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Runs building, brush, undo, and redo operations. Each player has at most
 * one operation in flight; world access happens on the owning region threads
 * through {@link RegionWorkQueue}.
 */
public final class BuildEngine {

    private final Sculpt plugin;
    private final BuildWorldWriter writer;
    private final EditHistory history;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private volatile BuildLimits limits;

    public BuildEngine(final Sculpt plugin, final BuildLimits limits) {
        this.plugin = plugin;
        this.writer = new BuildWorldWriter(plugin);
        this.limits = limits;
        this.history = new EditHistory(limits.historyMaxEntries(), limits.historyMaxBlocks());
    }

    public BuildLimits limits() {
        return limits;
    }

    public void reload(final BuildLimits limits) {
        this.limits = limits;
        history.configure(limits.historyMaxEntries(), limits.historyMaxBlocks());
    }

    public EditHistory history() {
        return history;
    }

    /** Reserve the player's single operation slot. */
    public boolean tryBegin(final Player player) {
        return busy.add(player.getUniqueId());
    }

    public void release(final Player player) {
        busy.remove(player.getUniqueId());
    }

    public boolean isBusy(final Player player) {
        return busy.contains(player.getUniqueId());
    }

    public void forget(final Player player) {
        history.clear(player.getUniqueId());
        busy.remove(player.getUniqueId());
    }

    // =====================================================================
    //  Cell edits
    // =====================================================================

    /** Outcome counters of one operation. */
    public static final class Report {
        public int changed;
        public int unchanged;
        public int protectedBlocks;
        public int obstructed;
        public int locked;
        public int limitReached;
        public int stale;
        public int failed;
        public boolean historyRecorded = true;

        void count(final BuildWorldWriter.Status status) {
            switch (status) {
                case CHANGED -> changed++;
                case UNCHANGED -> unchanged++;
                case PROTECTED -> protectedBlocks++;
                case OBSTRUCTED -> obstructed++;
                case LOCKED -> locked++;
                case LIMIT -> limitReached++;
                case STALE -> stale++;
            }
        }
    }

    /**
     * Apply per-block edits and record one undoable history entry. The
     * caller must hold the player's slot from {@link #tryBegin}; it is
     * released before {@code onDone} runs on the player's thread.
     */
    public void applyEdits(
            final Player player,
            final World world,
            final String label,
            final Map<BlockPos, BlockCellEdit> edits,
            final BuildWorldWriter.Strategies strategies,
            final boolean announce) {
        final Report report = new Report();
        final List<EditHistory.BlockChange> changes = new ArrayList<>();
        final FeedbackSound sound = new FeedbackSound();
        final List<BlockPos> positions = new ArrayList<>(edits.keySet());

        final RegionWorkQueue queue = new RegionWorkQueue(plugin, world, positions,
            position -> {
                final Block block = world.getBlockAt(position.x(), position.y(), position.z());
                final BlockCellEdit edit = edits.get(position);
                final BuildWorldWriter.Outcome outcome =
                    writer.applyEdit(player, block, edit, strategies);
                report.count(outcome.status());
                if (outcome.status() == BuildWorldWriter.Status.CHANGED) {
                    changes.add(new EditHistory.BlockChange(
                        position, outcome.before(), outcome.after()));
                    sound.record(block, edit.adds() ? edit.firstAddedMaterial() : null,
                        outcome.before().representativeMaterial(), edit.adds());
                }
                return outcome.work();
            },
            () -> !player.isOnline() || plugin.isDisabling(),
            (position, failure) -> recordFailure(report, world, position, failure),
            () -> {
                // A player who left mid-operation already had their history
                // discarded; recording now would only leak the snapshots.
                if (!changes.isEmpty() && player.isOnline()) {
                    report.historyRecorded = history.record(player.getUniqueId(),
                        new EditHistory.Entry(label, world.getUID(), changes));
                }
                release(player);
                if (announce) sendReport(player, label, report);
                else sendQuietReport(player, report);
            });
        queue.start();
    }

    // =====================================================================
    //  Undo / redo
    // =====================================================================

    /** Undo ({@code redo == false}) or redo up to {@code steps} entries. */
    public void revert(final Player player, final boolean redo, final int steps) {
        final int[] totals = new int[3]; // entries, restored blocks, skipped blocks
        revertNext(player, redo, Math.max(1, steps), totals, new Report());
    }

    private void revertNext(
            final Player player,
            final boolean redo,
            final int remaining,
            final int[] totals,
            final Report report) {
        final UUID playerId = player.getUniqueId();
        final EditHistory.Entry entry = remaining <= 0 ? null
            : redo ? history.popRedo(playerId) : history.popUndo(playerId);
        if (entry == null) {
            release(player);
            sendRevertReport(player, redo, totals, report);
            return;
        }
        final World world = Bukkit.getWorld(entry.worldId());
        if (world == null) {
            totals[2] += entry.size();
            revertNext(player, redo, remaining - 1, totals, report);
            return;
        }

        final Map<BlockPos, EditHistory.BlockChange> byPosition = new LinkedHashMap<>();
        for (final EditHistory.BlockChange change : entry.changes()) {
            byPosition.put(change.position(), change);
        }
        final List<EditHistory.BlockChange> inverse = new ArrayList<>();
        final RegionWorkQueue queue = new RegionWorkQueue(plugin, world,
            new ArrayList<>(byPosition.keySet()),
            position -> {
                final EditHistory.BlockChange change = byPosition.get(position);
                final Block block = world.getBlockAt(position.x(), position.y(), position.z());
                final BuildWorldWriter.Outcome outcome =
                    writer.restore(player, block, change.after(), change.before());
                report.count(outcome.status());
                if (outcome.status() == BuildWorldWriter.Status.CHANGED) {
                    inverse.add(new EditHistory.BlockChange(
                        position, change.after(), outcome.after()));
                    totals[1]++;
                } else {
                    totals[2]++;
                }
                return outcome.work();
            },
            () -> !player.isOnline() || plugin.isDisabling(),
            (position, failure) -> {
                recordFailure(report, world, position, failure);
                totals[2]++;
            },
            () -> {
                if (!player.isOnline() || plugin.isDisabling()) {
                    release(player);
                    return;
                }
                final EditHistory.Entry inverted =
                    new EditHistory.Entry(entry.label(), entry.worldId(), inverse);
                if (redo) history.pushUndo(playerId, inverted);
                else history.pushRedo(playerId, inverted);
                totals[0]++;
                revertNext(player, redo, remaining - 1, totals, report);
            });
        queue.start();
    }

    // =====================================================================
    //  Cell sampling for smoothing
    // =====================================================================

    /** Read occupancy of every cell in the given blocks at {@code grid}. */
    public void sample(
            final Player player,
            final World world,
            final Collection<BlockPos> positions,
            final int grid,
            final Consumer<BrushSmoother.CellLookup> onDone) {
        final Map<BlockPos, CellSample> samples = new ConcurrentHashMap<>();
        final RegionWorkQueue queue = new RegionWorkQueue(plugin, world,
            new ArrayList<>(positions),
            position -> {
                samples.put(position, sampleBlock(
                    world.getBlockAt(position.x(), position.y(), position.z()), grid));
                return 1;
            },
            () -> !player.isOnline() || plugin.isDisabling(),
            (position, failure) -> recordFailure(new Report(), world, position, failure),
            () -> onDone.accept(new SampleLookup(grid, samples)));
        queue.start();
    }

    private record CellSample(BitSet occupied, BlockData[] materials) {}

    private CellSample sampleBlock(final Block block, final int grid) {
        final int volume = grid * grid * grid;
        final BitSet occupied = new BitSet(volume);
        final BlockData[] materials = new BlockData[volume];
        final SculptBlock sculpt = writer.activeSculpt(block);
        if (sculpt != null) {
            final int side = 16 / grid;
            final int offset = side / 2;
            for (int index = 0; index < volume; index++) {
                final BlockData material = OctreeCellEditor.materialAt(sculpt.root,
                    CellVolume.localX(grid, index) * side + offset,
                    CellVolume.localY(grid, index) * side + offset,
                    CellVolume.localZ(grid, index) * side + offset);
                if (material == null) continue;
                occupied.set(index);
                materials[index] = material;
            }
        } else if (!BuildWorldWriter.isReplaceable(block)) {
            occupied.set(0, volume);
            final BlockData data = block.getBlockData();
            for (int index = 0; index < volume; index++) materials[index] = data;
        }
        return new CellSample(occupied, materials);
    }

    private record SampleLookup(int grid, Map<BlockPos, CellSample> samples)
            implements BrushSmoother.CellLookup {
        @Override
        public boolean occupied(final long x, final long y, final long z) {
            final CellSample sample = sampleAt(x, y, z);
            return sample != null && sample.occupied().get(index(x, y, z));
        }

        @Override
        public BlockData material(final long x, final long y, final long z) {
            final CellSample sample = sampleAt(x, y, z);
            return sample == null ? null : sample.materials()[index(x, y, z)];
        }

        private CellSample sampleAt(final long x, final long y, final long z) {
            return samples.get(new BlockPos(
                (int) Math.floorDiv(x, grid),
                (int) Math.floorDiv(y, grid),
                (int) Math.floorDiv(z, grid)));
        }

        private int index(final long x, final long y, final long z) {
            return CellVolume.localIndex(grid,
                (int) Math.floorMod(x, grid),
                (int) Math.floorMod(y, grid),
                (int) Math.floorMod(z, grid));
        }
    }

    // =====================================================================
    //  Sculpt mode click recording
    // =====================================================================

    /**
     * Start recording a synchronous Sculpt mode edit that can touch the
     * given blocks. Call {@link ClickRecording#finish()} on the same thread
     * after the edit to add an undo entry when anything changed.
     */
    public ClickRecording beginClick(final Player player, final Collection<Block> candidates) {
        final Map<BlockPos, Block> blocks = new LinkedHashMap<>();
        for (final Block block : candidates) {
            if (block == null || block.getWorld() != player.getWorld()) continue;
            blocks.putIfAbsent(new BlockPos(block.getX(), block.getY(), block.getZ()), block);
        }
        final Map<BlockPos, BlockSnapshot> before = new HashMap<>();
        blocks.forEach((position, block) -> before.put(position, writer.capture(block)));
        return new ClickRecording(player, blocks, before);
    }

    public final class ClickRecording {
        private final Player player;
        private final Map<BlockPos, Block> blocks;
        private final Map<BlockPos, BlockSnapshot> before;

        private ClickRecording(
                final Player player,
                final Map<BlockPos, Block> blocks,
                final Map<BlockPos, BlockSnapshot> before) {
            this.player = player;
            this.blocks = blocks;
            this.before = before;
        }

        public void finish() {
            final List<EditHistory.BlockChange> changes = new ArrayList<>();
            World world = null;
            for (final Map.Entry<BlockPos, Block> entry : blocks.entrySet()) {
                final BlockSnapshot after = writer.capture(entry.getValue());
                final BlockSnapshot previous = before.get(entry.getKey());
                if (previous.sameStateAs(after)) continue;
                changes.add(new EditHistory.BlockChange(entry.getKey(), previous, after));
                world = entry.getValue().getWorld();
            }
            if (world == null) return;
            history.record(player.getUniqueId(),
                new EditHistory.Entry("sculpt", world.getUID(), changes));
        }
    }

    // =====================================================================
    //  Feedback
    // =====================================================================

    /** Plays one representative sound for a multi-block operation. */
    private static final class FeedbackSound {
        private boolean played;

        void record(
                final Block block,
                final BlockData placed,
                final BlockData removed,
                final boolean placing) {
            if (played) return;
            played = true;
            final Location location = block.getLocation().add(0.5, 0.5, 0.5);
            if (placing) BlockEditSounds.playPlace(location, placed);
            else BlockEditSounds.playBreak(location, removed);
        }
    }

    private void recordFailure(
            final Report report,
            final World world,
            final BlockPos position,
            final RuntimeException failure) {
        report.failed++;
        plugin.getLogger().log(Level.WARNING, "[Sculpt] building edit failed at "
            + world.getName() + "," + position.x() + "," + position.y() + ","
            + position.z(), failure);
    }

    private void sendReport(final Player player, final String label, final Report report) {
        FoliaScheduler.runEntityTask(plugin, player, () -> {
            if (!player.isOnline()) return;
            if (report.changed > 0) {
                MessageUtil.sendTranslated(player, "building.result.completed",
                    label, report.changed);
            } else {
                MessageUtil.sendTranslated(player, "building.result.no_changes");
            }
            sendSkipCounts(player, report);
            if (!report.historyRecorded) {
                MessageUtil.sendTranslated(player, "building.result.not_recorded");
            }
        });
    }

    /** Brush strokes only surface problems, on the action bar. */
    private void sendQuietReport(final Player player, final Report report) {
        final String key;
        if (report.protectedBlocks > 0) key = "building.brush.protected";
        else if (report.limitReached > 0) key = "building.result.limit_reached";
        else if (report.failed > 0) key = "building.result.failed";
        else return;
        final int count = Math.max(report.protectedBlocks,
            Math.max(report.limitReached, report.failed));
        FoliaScheduler.runEntityTask(plugin, player, () -> {
            if (player.isOnline()) MessageUtil.sendTranslatedActionBar(player, key, count);
        });
    }

    private void sendRevertReport(
            final Player player,
            final boolean redo,
            final int[] totals,
            final Report report) {
        final String prefix = redo ? "building.redo." : "building.undo.";
        FoliaScheduler.runEntityTask(plugin, player, () -> {
            if (!player.isOnline()) return;
            if (totals[0] == 0) {
                MessageUtil.sendTranslated(player, prefix + "nothing");
                return;
            }
            MessageUtil.sendTranslated(player, prefix + "completed", totals[0], totals[1]);
            sendSkipCounts(player, report);
        });
    }

    private static void sendSkipCounts(final Player player, final Report report) {
        sendCount(player, "building.result.protected", report.protectedBlocks);
        sendCount(player, "building.result.obstructed", report.obstructed);
        sendCount(player, "building.result.locked", report.locked);
        sendCount(player, "building.result.limit_reached", report.limitReached);
        sendCount(player, "building.result.stale", report.stale);
        sendCount(player, "building.result.failed", report.failed);
    }

    private static void sendCount(final Player player, final String key, final int count) {
        if (count > 0) MessageUtil.sendTranslated(player, key, count);
    }
}
