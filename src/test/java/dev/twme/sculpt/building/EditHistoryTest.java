package dev.twme.sculpt.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class EditHistoryTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void recordingClearsRedoAndStacksAreLastInFirstOut() {
        final EditHistory history = new EditHistory(10, 100);
        final EditHistory.Entry first = entry("first", 1);
        final EditHistory.Entry second = entry("second", 1);
        history.record(PLAYER, first);
        history.record(PLAYER, second);

        assertSame(second, history.popUndo(PLAYER));
        history.pushRedo(PLAYER, second);
        assertEquals(1, history.redoSize(PLAYER));

        history.record(PLAYER, entry("third", 1));
        assertEquals(0, history.redoSize(PLAYER));
        assertEquals(List.of("third (1)", "first (1)"), history.undoLabels(PLAYER));
    }

    @Test
    void redoingPushesBackOntoUndoWithoutClearingRedo() {
        final EditHistory history = new EditHistory(10, 100);
        history.pushRedo(PLAYER, entry("a", 1));
        history.pushRedo(PLAYER, entry("b", 1));

        final EditHistory.Entry redone = history.popRedo(PLAYER);
        history.pushUndo(PLAYER, redone);
        assertEquals(1, history.redoSize(PLAYER));
        assertEquals(1, history.undoSize(PLAYER));
    }

    @Test
    void oldestEntriesAreDroppedByCount() {
        final EditHistory history = new EditHistory(2, 100);
        history.record(PLAYER, entry("one", 1));
        history.record(PLAYER, entry("two", 1));
        history.record(PLAYER, entry("three", 1));

        assertEquals(List.of("three (1)", "two (1)"), history.undoLabels(PLAYER));
    }

    @Test
    void oldestEntriesAreDroppedByBlockBudget() {
        final EditHistory history = new EditHistory(10, 5);
        history.record(PLAYER, entry("one", 3));
        history.record(PLAYER, entry("two", 3));

        assertEquals(List.of("two (3)"), history.undoLabels(PLAYER));
        assertFalse(history.record(PLAYER, entry("huge", 6)));
        assertEquals(List.of("two (3)"), history.undoLabels(PLAYER));
    }

    @Test
    void emptyEntriesAreIgnoredAndPlayersAreIsolated() {
        final EditHistory history = new EditHistory(10, 100);
        assertTrue(history.record(PLAYER, new EditHistory.Entry("none", WORLD, List.of())));
        assertEquals(0, history.undoSize(PLAYER));

        history.record(PLAYER, entry("mine", 1));
        assertNull(history.popUndo(UUID.randomUUID()));
        history.clear(PLAYER);
        assertNull(history.popUndo(PLAYER));
    }

    @Test
    void regularSnapshotsCompareBySerializedState() {
        final BlockSnapshot stone = BlockSnapshot.regular(TestBlockData.of(Material.STONE));
        final BlockSnapshot sameStone = BlockSnapshot.regular(TestBlockData.of(Material.STONE));
        final BlockSnapshot air = BlockSnapshot.regular(TestBlockData.of(Material.AIR));

        assertTrue(stone.sameStateAs(sameStone));
        assertFalse(stone.sameStateAs(air));
        assertTrue(stone.matches(TestBlockData.of(Material.STONE), null));
        assertFalse(stone.matches(TestBlockData.of(Material.DIRT), null));
    }

    private static EditHistory.Entry entry(final String label, final int blocks) {
        final List<EditHistory.BlockChange> changes = new ArrayList<>();
        for (int index = 0; index < blocks; index++) {
            changes.add(new EditHistory.BlockChange(new BlockPos(index, 0, 0),
                BlockSnapshot.regular(TestBlockData.of(Material.AIR)),
                BlockSnapshot.regular(TestBlockData.of(Material.STONE))));
        }
        return new EditHistory.Entry(label, WORLD, changes);
    }
}
