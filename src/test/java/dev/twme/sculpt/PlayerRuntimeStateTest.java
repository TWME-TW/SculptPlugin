package dev.twme.sculpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;

class PlayerRuntimeStateTest {

    @Test
    void reconnectCleanupRetainsSculptFillAndDisplayModes() {
        PlayerRuntimeState state = new PlayerRuntimeState();
        UUID playerId = UUID.randomUUID();
        state.setGridSize(playerId, 8);
        state.setHoverState(playerId, true);
        state.setSculptMode(playerId, true);
        state.setSculptModeSuspended(playerId, true);
        state.setFillMode(playerId, FillMode.NONE);
        state.setDisplayMode(playerId, SculptDisplayMode.TEXT_DISPLAY);

        state.clearTransient(playerId);

        assertEquals(4, state.gridSize(playerId, 4));
        assertNull(state.hoverState(playerId));
        assertTrue(state.sculptMode(playerId));
        assertFalse(state.sculptModeSuspended(playerId));
        assertEquals(FillMode.NONE, state.fillMode(playerId, FillMode.SHULKER));
        assertEquals(SculptDisplayMode.TEXT_DISPLAY,
            state.displayMode(playerId, SculptDisplayMode.HEAD));
    }

    @Test
    void disabledSculptModeUsesTheDefaultWithoutRetainingAnEntry() {
        PlayerRuntimeState state = new PlayerRuntimeState();
        UUID playerId = UUID.randomUUID();
        state.setSculptMode(playerId, true);

        state.setSculptMode(playerId, false);

        assertFalse(state.sculptMode(playerId));
        assertFalse(state.sculptModeSuspended(playerId));
    }

    @Test
    void suspensionOnlyExistsWhileSculptModeIsEnabled() {
        PlayerRuntimeState state = new PlayerRuntimeState();
        UUID playerId = UUID.randomUUID();

        state.setSculptModeSuspended(playerId, true);
        assertFalse(state.sculptModeSuspended(playerId));

        state.setSculptMode(playerId, true);
        state.setSculptModeSuspended(playerId, true);
        assertTrue(state.sculptModeSuspended(playerId));

        state.setSculptMode(playerId, false);
        assertFalse(state.sculptModeSuspended(playerId));
    }
}
