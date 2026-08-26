package dev.twme.sculpt.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.clipboard.Clipboard;

final class SculptClipboardNormalizer {

    private SculptClipboardNormalizer() {
    }

    static Result normalize(Clipboard clipboard) throws CleanupException {
        List<Entity> visibleTargets = SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(clipboard.getEntities());
        if (visibleTargets.isEmpty()) return new Result(0, 0);

        try {
            Clipboard owner = findEntityOwner(clipboard);
            List<Entity> redundant = SculptClipboardEntityFilter
                    .findRedundantPassengerSnapshots(owner.getEntities());
            if (redundant.size() != visibleTargets.size()) {
                return new Result(0, visibleTargets.size());
            }

            Method removeEntity = owner.getClass().getMethod("removeEntity", Entity.class);
            for (Entity entity : redundant) {
                try {
                    removeEntity.invoke(owner, entity);
                } catch (InvocationTargetException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof ReflectiveOperationException reflective) {
                        throw reflective;
                    }
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw exception;
                }
            }

            Set<Entity> remainingEntities =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            remainingEntities.addAll(owner.getEntities());
            int remaining = 0;
            for (Entity entity : redundant) {
                if (remainingEntities.contains(entity)) remaining++;
            }

            if (remaining == 0 && !SculptClipboardEntityFilter
                    .findRedundantPassengerSnapshots(clipboard.getEntities()).isEmpty()) {
                remaining = visibleTargets.size();
            }
            return new Result(redundant.size() - remaining, remaining);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            throw new CleanupException(visibleTargets.size(), exception);
        }
    }

    private static Clipboard findEntityOwner(Clipboard clipboard)
            throws ReflectiveOperationException {
        Set<Clipboard> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Clipboard current = clipboard;
        while (visited.add(current)) {
            Method getParent;
            try {
                getParent = current.getClass().getMethod("getParent");
            } catch (NoSuchMethodException ignored) {
                break;
            }

            Object parent = getParent.invoke(current);
            if (!(parent instanceof Clipboard next) || next == current) break;
            current = next;
        }
        return current;
    }

    record Result(int removedEntities, int remainingEntities) {
        boolean safe() {
            return remainingEntities == 0;
        }
    }

    static final class CleanupException extends Exception {
        private final int targetEntities;

        CleanupException(int targetEntities, Throwable cause) {
            super(cause);
            this.targetEntities = targetEntities;
        }

        int targetEntities() {
            return targetEntities;
        }
    }
}