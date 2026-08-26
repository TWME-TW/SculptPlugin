package dev.twme.sculpt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.plugin.SculptPermissions;

class SculptControlsListenerTest {

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
