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
        DoubleTapTracker<String> tracker = new DoubleTapTracker<>(WINDOW);
        UUID playerId = UUID.randomUUID();
        Runnable single = () -> { };

        DoubleTapTracker.Registration<String> first = tracker.register(
            playerId, "sculpt", 1_000L, single);
        DoubleTapTracker.Registration<String> second = tracker.register(
            playerId, "sculpt", 1_250L, () -> { });

        assertFalse(first.doubleTap());
        assertTrue(second.doubleTap());
        assertSame(first.pending(), second.previous());
        assertFalse(tracker.expire(first.pending()));
    }

    @Test
    void expiredOrDifferentContextStartsANewSequence() {
        DoubleTapTracker<String> tracker = new DoubleTapTracker<>(WINDOW);
        UUID playerId = UUID.randomUUID();

        DoubleTapTracker.Registration<String> first = tracker.register(
            playerId, "active", 1_000L, () -> { });
        DoubleTapTracker.Registration<String> expired = tracker.register(
            playerId, "active", 1_301L, () -> { });
        DoubleTapTracker.Registration<String> changed = tracker.register(
            playerId, "blueprint", 1_400L, () -> { });

        assertFalse(expired.doubleTap());
        assertSame(first.pending(), expired.previous());
        assertFalse(changed.doubleTap());
        assertSame(expired.pending(), changed.previous());
        assertTrue(tracker.expire(changed.pending()));
    }
}
