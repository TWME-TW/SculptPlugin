package dev.twme.sculpt.building;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player undo and redo stacks for building, brush, and Sculpt mode edits.
 *
 * <p>Undo and redo are symmetric: reverting an entry restores each change's
 * {@code before} state where the world still shows its {@code after} state,
 * and yields the inverse entry for the opposite stack.
 */
public final class EditHistory {

    /** One changed position; {@code after} is what the edit left behind. */
    public record BlockChange(BlockPos position, BlockSnapshot before, BlockSnapshot after) {}

    /** One undoable action affecting one world. */
    public record Entry(String label, UUID worldId, List<BlockChange> changes) {
        public Entry {
            changes = List.copyOf(changes);
        }

        public int size() {
            return changes.size();
        }
    }

    private static final class Stacks {
        private final Deque<Entry> undo = new ArrayDeque<>();
        private final Deque<Entry> redo = new ArrayDeque<>();
    }

    private final Map<UUID, Stacks> players = new HashMap<>();
    private volatile int maxEntries;
    private volatile int maxBlocks;

    public EditHistory(final int maxEntries, final int maxBlocks) {
        configure(maxEntries, maxBlocks);
    }

    public void configure(final int maxEntries, final int maxBlocks) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxBlocks = Math.max(1, maxBlocks);
    }

    /**
     * Record a new action. Any redo history is discarded, like in an editor.
     *
     * @return {@code false} when the entry alone exceeds the per-player budget
     */
    public synchronized boolean record(final UUID player, final Entry entry) {
        if (entry.changes().isEmpty()) return true;
        final Stacks stacks = players.computeIfAbsent(player, ignored -> new Stacks());
        stacks.redo.clear();
        if (entry.size() > maxBlocks) return false;
        stacks.undo.push(entry);
        trim(stacks.undo);
        return true;
    }

    public synchronized Entry popUndo(final UUID player) {
        final Stacks stacks = players.get(player);
        return stacks == null ? null : stacks.undo.poll();
    }

    public synchronized Entry popRedo(final UUID player) {
        final Stacks stacks = players.get(player);
        return stacks == null ? null : stacks.redo.poll();
    }

    /** Push the inverse of an undone entry so it can be redone. */
    public synchronized void pushRedo(final UUID player, final Entry entry) {
        if (entry.changes().isEmpty()) return;
        final Stacks stacks = players.computeIfAbsent(player, ignored -> new Stacks());
        stacks.redo.push(entry);
        trim(stacks.redo);
    }

    /** Push the inverse of a redone entry without clearing the redo stack. */
    public synchronized void pushUndo(final UUID player, final Entry entry) {
        if (entry.changes().isEmpty()) return;
        final Stacks stacks = players.computeIfAbsent(player, ignored -> new Stacks());
        stacks.undo.push(entry);
        trim(stacks.undo);
    }

    public synchronized int undoSize(final UUID player) {
        final Stacks stacks = players.get(player);
        return stacks == null ? 0 : stacks.undo.size();
    }

    public synchronized int redoSize(final UUID player) {
        final Stacks stacks = players.get(player);
        return stacks == null ? 0 : stacks.redo.size();
    }

    /** Labels from newest to oldest, for {@code /sculpt history}. */
    public synchronized List<String> undoLabels(final UUID player) {
        final Stacks stacks = players.get(player);
        return stacks == null ? List.of()
            : stacks.undo.stream().map(entry -> entry.label() + " (" + entry.size() + ")").toList();
    }

    public synchronized void clear(final UUID player) {
        players.remove(player);
    }

    public synchronized void clearAll() {
        players.clear();
    }

    private void trim(final Deque<Entry> stack) {
        while (stack.size() > maxEntries) stack.removeLast();
        long total = 0L;
        final Iterator<Entry> newestFirst = stack.iterator();
        int kept = 0;
        while (newestFirst.hasNext()) {
            total += newestFirst.next().size();
            if (total > maxBlocks && kept > 0) break;
            kept++;
        }
        while (stack.size() > kept) stack.removeLast();
    }
}
