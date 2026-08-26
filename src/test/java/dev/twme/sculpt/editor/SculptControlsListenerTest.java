package dev.twme.sculpt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.plugin.SculptPermissions;

class SculptControlsListenerTest {

    @Test
    void doubleTapWindowRoundsUpToWholeServerTicks() {
        assertEquals(1L, SculptControlsListener.doubleTapDelayTicks(50));
        assertEquals(6L, SculptControlsListener.doubleTapDelayTicks(300));
        assertEquals(7L, SculptControlsListener.doubleTapDelayTicks(301));
        assertThrows(IllegalArgumentException.class,
            () -> SculptControlsListener.doubleTapDelayTicks(0));
    }

    @Test
    void suspensionShortcutRequiresSneakingAndEnabledSculptMode() {
        assertTrue(SculptControlsListener.isSuspensionShortcut(true, true));
        assertFalse(SculptControlsListener.isSuspensionShortcut(false, true));
        assertFalse(SculptControlsListener.isSuspensionShortcut(true, false));
    }

    @Test
    void fillCycleUsesTheDesignedOrder() {
        Set<String> permitted = Set.of(
            SculptPermissions.FILL_SHULKER,
            SculptPermissions.FILL_BARRIER,
            SculptPermissions.FILL_NULL);

        assertEquals(FillMode.BARRIER,
            SculptControlsListener.nextAllowedFillMode(
                FillMode.SHULKER, permitted::contains));
        assertEquals(FillMode.NONE,
            SculptControlsListener.nextAllowedFillMode(
                FillMode.BARRIER, permitted::contains));
        assertEquals(FillMode.SHULKER,
            SculptControlsListener.nextAllowedFillMode(
                FillMode.NONE, permitted::contains));
    }

    @Test
    void fillCycleSkipsModesWithoutPermission() {
        Set<String> permitted = Set.of(
            SculptPermissions.FILL_SHULKER,
            SculptPermissions.FILL_NULL);

        assertEquals(FillMode.NONE,
            SculptControlsListener.nextAllowedFillMode(
                FillMode.SHULKER, permitted::contains));
    }

    @Test
    void displayCycleUsesAutoHeadTextDisplayOrder() {
        Set<String> permitted = Set.of(
            SculptPermissions.DISPLAY_AUTO,
            SculptPermissions.DISPLAY_HEAD,
            SculptPermissions.DISPLAY_TEXTDISPLAY);

        assertEquals(SculptDisplayMode.HEAD,
            SculptControlsListener.nextAllowedDisplayMode(
                SculptDisplayMode.AUTO, permitted::contains));
        assertEquals(SculptDisplayMode.TEXT_DISPLAY,
            SculptControlsListener.nextAllowedDisplayMode(
                SculptDisplayMode.HEAD, permitted::contains));
        assertEquals(SculptDisplayMode.AUTO,
            SculptControlsListener.nextAllowedDisplayMode(
                SculptDisplayMode.TEXT_DISPLAY, permitted::contains));
    }

    @Test
    void cycleReturnsNullWhenNoChoiceIsPermitted() {
        assertNull(SculptControlsListener.nextAllowedFillMode(
            FillMode.SHULKER, ignored -> false));
        assertNull(SculptControlsListener.nextAllowedDisplayMode(
            SculptDisplayMode.AUTO, ignored -> false));
    }
}
