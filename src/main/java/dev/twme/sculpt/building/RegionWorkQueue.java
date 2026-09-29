package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

import org.bukkit.Location;
import org.bukkit.World;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.util.FoliaScheduler;

/**
 * Visits block positions one chunk at a time on the owning region thread,
 * spreading heavy work over several ticks. Chunks are processed strictly in
 * sequence, so callers can accumulate results without extra locking beyond
 * the happens-before edges provided by the scheduler.
 */
final class RegionWorkQueue {

    /** Display-entity work allowed per tick before yielding. */
    static final int WORK_PER_TICK = 2048;

    private final Sculpt plugin;
    private final World world;
    private final List<List<BlockPos>> chunks;
    private final ToIntFunction<BlockPos> worker;
    private final BooleanSupplier cancelled;
    private final Runnable onFinish;
    private final BiConsumer<BlockPos, RuntimeException> onFailure;
    private final int total;
    private IntConsumer onProgress = ignored -> {};
    private int processed;
    private int chunkIndex;
    private int positionIndex;

    /**
     * @param worker    processes one position and returns its work units
     * @param cancelled polled between slices; stops the queue when true
     * @param onFailure receives unexpected failures of individual positions
     * @param onFinish  runs exactly once after all positions or cancellation
     */
    RegionWorkQueue(
            final Sculpt plugin,
            final World world,
            final List<BlockPos> positions,
            final ToIntFunction<BlockPos> worker,
            final BooleanSupplier cancelled,
            final BiConsumer<BlockPos, RuntimeException> onFailure,
            final Runnable onFinish) {
        this.plugin = plugin;
        this.world = world;
        this.worker = worker;
        this.cancelled = cancelled;
        this.onFailure = onFailure;
        this.onFinish = onFinish;
        this.chunks = groupByChunk(positions);
        this.total = positions.size();
    }

    /** Receives the number of processed positions after every slice, on the region thread. */
    RegionWorkQueue onProgress(final IntConsumer listener) {
        this.onProgress = listener;
        return this;
    }

    int total() {
        return total;
    }

    static List<List<BlockPos>> groupByChunk(final List<BlockPos> positions) {
        final List<BlockPos> sorted = new ArrayList<>(positions);
        sorted.sort(BlockPos.CHUNK_ORDER);
        final List<List<BlockPos>> groups = new ArrayList<>();
        List<BlockPos> current = null;
        for (final BlockPos position : sorted) {
            if (current == null || !current.getFirst().sameChunk(position)) {
                current = new ArrayList<>();
                groups.add(current);
            }
            current.add(position);
        }
        return groups;
    }

    void start() {
        scheduleNext();
    }

    private void scheduleNext() {
        if (chunkIndex >= chunks.size() || cancelled.getAsBoolean()) {
            onFinish.run();
            return;
        }
        final BlockPos first = chunks.get(chunkIndex).get(positionIndex);
        try {
            FoliaScheduler.runRegionTaskLater(plugin,
                new Location(world, first.x(), first.y(), first.z()),
                this::runSlice, 1L);
        } catch (final RuntimeException schedulingFailure) {
            onFailure.accept(first, schedulingFailure);
            chunkIndex++;
            positionIndex = 0;
            scheduleNext();
        }
    }

    private void runSlice() {
        if (cancelled.getAsBoolean()) {
            onFinish.run();
            return;
        }
        int budget = WORK_PER_TICK;
        while (chunkIndex < chunks.size() && budget > 0) {
            final List<BlockPos> chunk = chunks.get(chunkIndex);
            final BlockPos position = chunk.get(positionIndex);
            int work = 1;
            try {
                if (positionIndex == 0) world.getChunkAt(position.chunkX(), position.chunkZ());
                work = Math.max(1, worker.applyAsInt(position));
            } catch (final RuntimeException failure) {
                onFailure.accept(position, failure);
            }
            budget -= work;
            processed++;
            positionIndex++;
            if (positionIndex >= chunk.size()) {
                chunkIndex++;
                positionIndex = 0;
                // A different chunk may belong to another Folia region.
                if (FoliaScheduler.isFolia()) break;
            }
        }
        onProgress.accept(processed);
        scheduleNext();
    }
}
