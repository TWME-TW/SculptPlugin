package dev.twme.sculpt.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class DoubleTapTrackerTest {

    private static final long WINDOW = 300L;

    @Test
    void matchingSecondTapWithinWindowBecomesDoubleTap() {
        DoubleTapTracker<String> tracker = new DoubleTapTracker<>();
        UUID playerId = UUID.randomUUID();
        Runnable single = () -> { };

        DoubleTapTracker.Registration<String> first = tracker.register(
            playerId, "sculpt", 1_000L, WINDOW, single);
        DoubleTapTracker.Registration<String> second = tracker.register(
            playerId, "sculpt", 1_250L, WINDOW, () -> { });

        assertFalse(first.doubleTap());
        assertTrue(second.doubleTap());
        assertSame(first.pending(), second.previous());
        assertFalse(tracker.expire(first.pending()));
    }

    @Test
    void expiredOrDifferentContextStartsANewSequence() {
        DoubleTapTracker<String> tracker = new DoubleTapTracker<>();
        UUID playerId = UUID.randomUUID();

        DoubleTapTracker.Registration<String> first = tracker.register(
            playerId, "active", 1_000L, WINDOW, () -> { });
        DoubleTapTracker.Registration<String> expired = tracker.register(
            playerId, "active", 1_301L, WINDOW, () -> { });
        DoubleTapTracker.Registration<String> changed = tracker.register(
            playerId, "blueprint", 1_400L, WINDOW, () -> { });

        assertFalse(expired.doubleTap());
        assertSame(first.pending(), expired.previous());
        assertFalse(changed.doubleTap());
        assertSame(expired.pending(), changed.previous());
        assertTrue(tracker.expire(changed.pending()));
    }

    @Test
    void firstTapRetainsItsConfiguredWindow() {
        DoubleTapTracker<String> tracker = new DoubleTapTracker<>();
        UUID playerId = UUID.randomUUID();

        DoubleTapTracker.Registration<String> first = tracker.register(
            playerId, "sculpt", 1_000L, 200L, () -> { });
        DoubleTapTracker.Registration<String> second = tracker.register(
            playerId, "sculpt", 1_250L, 500L, () -> { });

        assertFalse(second.doubleTap());
        assertSame(first.pending(), second.previous());
    }
}
