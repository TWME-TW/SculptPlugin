package dev.twme.sculpt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;

class PlayerRuntimeStateTest {

    @Test
    void reconnectCleanupRetainsFillAndDisplayModes() {
        PlayerRuntimeState state = new PlayerRuntimeState();
        UUID playerId = UUID.randomUUID();
        state.setGridSize(playerId, 8);
        state.setFillMode(playerId, FillMode.NONE);
        state.setDisplayMode(playerId, SculptDisplayMode.TEXT_DISPLAY);

        state.clearTransient(playerId);

        assertEquals(4, state.gridSize(playerId, 4));
        assertEquals(FillMode.NONE, state.fillMode(playerId, FillMode.SHULKER));
        assertEquals(SculptDisplayMode.TEXT_DISPLAY,
            state.displayMode(playerId, SculptDisplayMode.HEAD));
    }
}
