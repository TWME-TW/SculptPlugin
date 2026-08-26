package dev.twme.sculpt;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;

/** Holds per-player choices for the lifetime of one server process. */
final class PlayerRuntimeState {

    private final Map<UUID, Integer> gridSizes = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> hoverStates = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> sculptModes = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> suspendedSculptModes = new ConcurrentHashMap<>();
    private final Map<UUID, FillMode> fillModes = new ConcurrentHashMap<>();
    private final Map<UUID, SculptDisplayMode> displayModes = new ConcurrentHashMap<>();

    int gridSize(final UUID playerId, final int fallback) {
        return gridSizes.getOrDefault(playerId, fallback);
    }

    void setGridSize(final UUID playerId, final int gridSize) {
        gridSizes.put(playerId, gridSize);
    }

    @Nullable
    Boolean hoverState(final UUID playerId) {
        return hoverStates.get(playerId);
    }

    void setHoverState(final UUID playerId, @Nullable final Boolean state) {
        if (state == null) {
            hoverStates.remove(playerId);
        } else {
            hoverStates.put(playerId, state);
        }
    }

    boolean sculptMode(final UUID playerId) {
        return sculptModes.getOrDefault(playerId, false);
    }

    void setSculptMode(final UUID playerId, final boolean enabled) {
        if (enabled) {
            sculptModes.put(playerId, true);
        } else {
            sculptModes.remove(playerId);
            suspendedSculptModes.remove(playerId);
        }
    }

    boolean sculptModeSuspended(final UUID playerId) {
        return sculptMode(playerId)
            && suspendedSculptModes.getOrDefault(playerId, false);
    }

    void setSculptModeSuspended(final UUID playerId, final boolean suspended) {
        if (suspended && sculptMode(playerId)) {
            suspendedSculptModes.put(playerId, true);
        } else {
            suspendedSculptModes.remove(playerId);
        }
    }

    FillMode fillMode(final UUID playerId, final FillMode fallback) {
        return fillModes.getOrDefault(playerId, fallback);
    }

    void setFillMode(final UUID playerId, final FillMode mode) {
        fillModes.put(playerId, mode);
    }

    SculptDisplayMode displayMode(
            final UUID playerId,
            final SculptDisplayMode fallback) {
        return displayModes.getOrDefault(playerId, fallback);
    }

    void setDisplayMode(final UUID playerId, final SculptDisplayMode mode) {
        displayModes.put(playerId, mode);
    }

    /** Clears connection-only choices while retaining Sculpt mode selections. */
    void clearTransient(final UUID playerId) {
        gridSizes.remove(playerId);
        hoverStates.remove(playerId);
        suspendedSculptModes.remove(playerId);
    }
}
