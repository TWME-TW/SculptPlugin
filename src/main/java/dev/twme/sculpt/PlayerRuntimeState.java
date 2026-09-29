package dev.twme.sculpt;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;

/** Holds per-player choices for the lifetime of one server process. */
final class PlayerRuntimeState {

    private final Map<UUID, Integer> gridSizes = new ConcurrentHashMap<>();
    private final Map<UUID, FillMode> fillModes = new ConcurrentHashMap<>();
    private final Map<UUID, SculptDisplayMode> displayModes = new ConcurrentHashMap<>();

    int gridSize(final UUID playerId, final int fallback) {
        return gridSizes.getOrDefault(playerId, fallback);
    }

    void setGridSize(final UUID playerId, final int gridSize) {
        gridSizes.put(playerId, gridSize);
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

    /** Clears connection-only choices while retaining fill and display selections. */
    void clearTransient(final UUID playerId) {
        gridSizes.remove(playerId);
    }
}
