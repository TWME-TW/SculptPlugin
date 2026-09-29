package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
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
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.PlayerHeadTexture;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.util.BlockEditSounds;
import dev.twme.sculpt.util.FoliaScheduler;

/**
 * Applies cell edits, undo, and redo. Each player has at most one operation
 * in flight; world access happens on the owning region threads through
 * {@link RegionWorkQueue}, and results are reported to an
 * {@link EditObserver} on the player's thread.
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

    /**
     * Apply per-block edits and record one undoable history entry. The
     * caller must hold the player's slot from {@link #tryBegin}; it is
     * released before the observer's {@code onFinish} runs.
     */
    public void applyEdits(
            final Player player,
            final World world,
            final String label,
            final Map<BlockPos, BlockCellEdit> edits,
            final BuildWorldWriter.Strategies strategies,
            final EditObserver observer) {
        final EditReport report = new EditReport();
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
                finish(player, observer, report, changes);
            });
        queue.onProgress(done -> progress(player, observer, done, positions.size()));
        queue.start();
    }

    // =====================================================================
    //  Undo / redo
    // =====================================================================

    /**
     * Undo ({@code redo == false}) or redo up to {@code steps} entries. The
     * caller must hold the player's slot from {@link #tryBegin}.
     */
    public void revert(
            final Player player,
            final boolean redo,
            final int steps,
            final EditObserver observer) {
        revertNext(player, redo, Math.max(1, steps), new EditReport(), new ArrayList<>(), observer);
    }

    private void revertNext(
            final Player player,
            final boolean redo,
            final int remaining,
            final EditReport report,
            final List<EditHistory.BlockChange> allChanged,
            final EditObserver observer) {
        final UUID playerId = player.getUniqueId();
        final EditHistory.Entry entry = remaining <= 0 ? null
            : redo ? history.popRedo(playerId) : history.popUndo(playerId);
        if (entry == null) {
            release(player);
            finish(player, observer, report, allChanged);
            return;
        }
        final World world = Bukkit.getWorld(entry.worldId());
        if (world == null) {
            // The world was unloaded; its snapshots can no longer be applied.
            report.stale += entry.size();
            revertNext(player, redo, remaining - 1, report, allChanged, observer);
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
                }
                return outcome.work();
            },
            () -> !player.isOnline() || plugin.isDisabling(),
            (position, failure) -> recordFailure(report, world, position, failure),
            () -> {
                if (!player.isOnline() || plugin.isDisabling()) {
                    release(player);
                    return;
                }
                final EditHistory.Entry inverted =
                    new EditHistory.Entry(entry.label(), entry.worldId(), inverse);
                if (redo) history.pushUndo(playerId, inverted);
                else history.pushRedo(playerId, inverted);
                report.entries++;
                allChanged.addAll(inverse);
                revertNext(player, redo, remaining - 1, report, allChanged, observer);
            });
        queue.onProgress(done -> progress(player, observer, done, byPosition.size()));
        queue.start();
    }

    // =====================================================================
    //  External changes
    // =====================================================================

    /**
     * Record a synchronous change made by another system, such as a
     * blueprint paste, as one undoable entry. {@code blocks} must all belong
     * to the current thread's region; they are captured before and after
     * {@code change} runs, and only positions that differ are recorded.
     *
     * @return the positions that changed
     */
    public List<BlockPos> recordChange(
            final Player player,
            final String label,
            final Collection<Block> blocks,
            final Runnable change) {
        final Map<BlockPos, Block> byPosition = new LinkedHashMap<>();
        for (final Block block : blocks) {
            byPosition.putIfAbsent(new BlockPos(block.getX(), block.getY(), block.getZ()), block);
        }
        final Map<BlockPos, BlockSnapshot> before = new LinkedHashMap<>();
        byPosition.forEach((position, block) -> before.put(position, writer.capture(block)));
        change.run();
        final List<EditHistory.BlockChange> changes = new ArrayList<>();
        World world = null;
        for (final Map.Entry<BlockPos, Block> entry : byPosition.entrySet()) {
            final BlockSnapshot after = writer.capture(entry.getValue());
            final BlockSnapshot previous = before.get(entry.getKey());
            if (previous.sameStateAs(after)) continue;
            changes.add(new EditHistory.BlockChange(entry.getKey(), previous, after));
            world = entry.getValue().getWorld();
        }
        final List<BlockPos> changed = new ArrayList<>(changes.size());
        for (final EditHistory.BlockChange each : changes) changed.add(each.position());
        if (world != null) {
            history.record(player.getUniqueId(), new EditHistory.Entry(label, world.getUID(), changes));
        }
        return changed;
    }

    // =====================================================================
    //  Cell sampling
    // =====================================================================

    /** Read occupancy and materials of every cell in the given blocks at {@code grid}. */
    public void sample(
            final Player player,
            final World world,
            final Collection<BlockPos> positions,
            final int grid,
            final Consumer<CellSamples> onDone) {
        final Map<BlockPos, CellSamples.Sample> samples = new ConcurrentHashMap<>();
        final RegionWorkQueue queue = new RegionWorkQueue(plugin, world,
            new ArrayList<>(positions),
            position -> {
                samples.put(position, sampleBlock(
                    world.getBlockAt(position.x(), position.y(), position.z()), grid));
                return 1;
            },
            () -> !player.isOnline() || plugin.isDisabling(),
            (position, failure) -> recordFailure(new EditReport(), world, position, failure),
            () -> onDone.accept(new CellSamples(grid, samples)));
        queue.start();
    }

    private CellSamples.Sample sampleBlock(final Block block, final int grid) {
        final int volume = grid * grid * grid;
        final BitSet occupied = new BitSet(volume);
        final BlockData[] materials = new BlockData[volume];
        final PlayerHeadTexture[] textures = new PlayerHeadTexture[volume];
        final SculptBlock sculpt = writer.activeSculpt(block);
        if (sculpt != null) {
            final int side = 16 / grid;
            final int offset = side / 2;
            for (int index = 0; index < volume; index++) {
                final OctreeNode leaf = sculpt.root.findLeaf(
                    CellVolume.localX(grid, index) * side + offset,
                    CellVolume.localY(grid, index) * side + offset,
                    CellVolume.localZ(grid, index) * side + offset);
                if (leaf == null || leaf.isRemoved()) continue;
                occupied.set(index);
                materials[index] = leaf.blockData() == null
                    ? sculpt.originalBlockData : leaf.blockData();
                textures[index] = leaf.playerHeadTexture();
            }
        } else if (!BuildWorldWriter.isReplaceable(block)) {
            occupied.set(0, volume);
            final BlockData data = block.getBlockData();
            final boolean editable = writer.isConvertible(block);
            for (int index = 0; index < volume; index++) {
                materials[index] = editable ? data : null;
            }
        }
        return new CellSamples.Sample(occupied, materials, textures);
    }

    // =====================================================================
    //  Feedback
    // =====================================================================

    private void progress(
            final Player player,
            final EditObserver observer,
            final int done,
            final int total) {
        if (observer == EditObserver.NONE || !player.isOnline()) return;
        FoliaScheduler.runEntityTask(plugin, player, () -> observer.onProgress(done, total));
    }

    private void finish(
            final Player player,
            final EditObserver observer,
            final EditReport report,
            final List<EditHistory.BlockChange> changes) {
        if (!player.isOnline()) return;
        final List<BlockPos> changed = new ArrayList<>(changes.size());
        for (final EditHistory.BlockChange change : changes) changed.add(change.position());
        FoliaScheduler.runEntityTask(plugin, player, () -> {
            if (player.isOnline()) observer.onFinish(report, changed);
        });
    }

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
            final EditReport report,
            final World world,
            final BlockPos position,
            final RuntimeException failure) {
        report.failed++;
        plugin.getLogger().log(Level.WARNING, "[Sculpt] building edit failed at "
            + world.getName() + "," + position.x() + "," + position.y() + ","
            + position.z(), failure);
    }
}
