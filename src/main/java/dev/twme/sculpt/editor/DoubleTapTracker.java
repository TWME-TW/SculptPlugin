package dev.twme.sculpt.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Classifies delayed single taps and double taps without depending on Bukkit.
 * The caller owns scheduling so the same logic works on Paper and Folia.
 */
final class DoubleTapTracker<C> {

    private final long windowNanos;
    private final Map<UUID, PendingTap<C>> pending = new HashMap<>();

    DoubleTapTracker(final long windowNanos) {
        if (windowNanos <= 0L) {
            throw new IllegalArgumentException("windowNanos must be positive");
        }
        this.windowNanos = windowNanos;
    }

    synchronized Registration<C> register(
            final UUID playerId,
            final C context,
            final long nowNanos,
            final Runnable singleAction) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(singleAction, "singleAction");

        final PendingTap<C> previous = pending.get(playerId);
        if (previous != null
                && previous.context().equals(context)
                && nowNanos >= previous.startedAtNanos()
                && nowNanos - previous.startedAtNanos() <= windowNanos) {
            pending.remove(playerId);
            return new Registration<>(true, null, previous);
        }

        final PendingTap<C> next = new PendingTap<>(
            playerId, context, nowNanos, singleAction);
        pending.put(playerId, next);
        return new Registration<>(false, next, previous);
    }

    synchronized boolean expire(final PendingTap<C> tap) {
        return pending.remove(tap.playerId(), tap);
    }

    synchronized PendingTap<C> clear(final UUID playerId) {
        return pending.remove(playerId);
    }

    synchronized List<PendingTap<C>> clearAll() {
        final List<PendingTap<C>> result = new ArrayList<>(pending.values());
        pending.clear();
        return result;
    }

    record Registration<C>(
        boolean doubleTap,
        PendingTap<C> pending,
        PendingTap<C> previous
    ) {}

    static final class PendingTap<C> {
        private final UUID playerId;
        private final C context;
        private final long startedAtNanos;
        private final Runnable singleAction;
        private volatile Object taskHandle;

        PendingTap(
                final UUID playerId,
                final C context,
                final long startedAtNanos,
                final Runnable singleAction) {
            this.playerId = playerId;
            this.context = context;
            this.startedAtNanos = startedAtNanos;
            this.singleAction = singleAction;
        }

        UUID playerId() {
            return playerId;
        }

        C context() {
            return context;
        }

        long startedAtNanos() {
            return startedAtNanos;
        }

        Runnable singleAction() {
            return singleAction;
        }

        Object taskHandle() {
            return taskHandle;
        }

        void taskHandle(final Object taskHandle) {
            this.taskHandle = taskHandle;
        }
    }
}
