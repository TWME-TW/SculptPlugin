package dev.twme.sculpt.plugin;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the grid-size permission helpers in {@link SculptCommand}.
 *
 * <p>These tests use the {@code Predicate<String>} overloads so they run
 * without a live Bukkit server. The {@code Player}-based overloads simply
 * delegate to these and are exercised via integration tests.
 */
class SculptCommandPermissionTest {

    /** Build a permission checker that grants the given permission nodes. */
    private static Predicate<String> checker(Set<String> granted) {
        return granted::contains;
    }

    private static Predicate<String> checker(String... perms) {
        Set<String> set = new HashSet<>();
        for (String p : perms) set.add(p);
        return checker(set);
    }

    private static Predicate<String> empty() {
        return checker(Set.of());
    }

    // ---------------------------------------------------------------
    //  canUseGrid
    // ---------------------------------------------------------------

    @Test
    void canUseGrid_nullCheckerReturnsFalse() {
        assertFalse(SculptCommand.canUseGrid((Predicate<String>) null, 4));
    }

    @Test
    void canUseGrid_invalidSizeReturnsFalse() {
        Predicate<String> op = checker("sculpt.command.resolution.4");
        assertFalse(SculptCommand.canUseGrid(op, 3));
        assertFalse(SculptCommand.canUseGrid(op, 7));
    }

    @Test
    void canUseGrid_grantedReturnsTrue() {
        Predicate<String> op = checker("sculpt.command.resolution.4");
        assertTrue(SculptCommand.canUseGrid(op, 4));
    }

    @Test
    void canUseGrid_notGrantedReturnsFalse() {
        Predicate<String> op = checker("sculpt.command.resolution.4");
        assertFalse(SculptCommand.canUseGrid(op, 8));
    }

    @Test
    void canUseGrid_wildcardGrantsAll() {
        // plugin.yml grants every specific size as a wildcard child. Simulate
        // the resolved permissions by including all leaves in the granted set.
        Predicate<String> op = checker(
                "sculpt.command.resolution.1", "sculpt.command.resolution.2",
                "sculpt.command.resolution.4", "sculpt.command.resolution.8",
                "sculpt.command.resolution.16");
        for (int g : new int[]{1, 2, 4, 8, 16}) {
            assertTrue(SculptCommand.canUseGrid(op, g),
                    "wildcard should grant grid " + g);
        }
    }

    // ---------------------------------------------------------------
    //  largestAllowedGrid
    // ---------------------------------------------------------------

    @Test
    void largestAllowedGrid_nullReturnsMinusOne() {
        assertEquals(-1, SculptCommand.largestAllowedGrid((Predicate<String>) null));
    }

    @Test
    void largestAllowedGrid_emptyReturnsMinusOne() {
        assertEquals(-1, SculptCommand.largestAllowedGrid(empty()));
    }

    @Test
    void largestAllowedGrid_onlySmallestGranted() {
        Predicate<String> op = checker("sculpt.command.resolution.1");
        assertEquals(1, SculptCommand.largestAllowedGrid(op));
    }

    @Test
    void largestAllowedGrid_picksActualLargest() {
        Predicate<String> op = checker(
                "sculpt.command.resolution.1", "sculpt.command.resolution.4",
                "sculpt.command.resolution.8");
        assertEquals(8, SculptCommand.largestAllowedGrid(op));
    }

    @Test
    void largestAllowedGrid_allGranted() {
        Predicate<String> op = checker(
                "sculpt.command.resolution.1", "sculpt.command.resolution.2",
                "sculpt.command.resolution.4", "sculpt.command.resolution.8",
                "sculpt.command.resolution.16");
        assertEquals(16, SculptCommand.largestAllowedGrid(op));
    }

    // ---------------------------------------------------------------
    //  allowedGridSizes
    // ---------------------------------------------------------------

    @Test
    void allowedGridSizes_nullReturnsEmpty() {
        assertEquals(List.of(),
                SculptCommand.allowedGridSizes((Predicate<String>) null));
    }

    @Test
    void allowedGridSizes_emptyReturnsEmpty() {
        assertEquals(List.of(), SculptCommand.allowedGridSizes(empty()));
    }

    @Test
    void allowedGridSizes_returnsInOrder() {
        Predicate<String> op = checker(
                "sculpt.command.resolution.1", "sculpt.command.resolution.4");
        assertEquals(List.of("1", "4"),
                SculptCommand.allowedGridSizes(op));
    }

    @Test
    void allowedGridSizes_allGranted() {
        Predicate<String> op = checker(
                "sculpt.command.resolution.1", "sculpt.command.resolution.2",
                "sculpt.command.resolution.4", "sculpt.command.resolution.8",
                "sculpt.command.resolution.16");
        assertEquals(List.of("1", "2", "4", "8", "16"),
                SculptCommand.allowedGridSizes(op));
    }

    @Test
    void allowedGridSizes_skipsNonContiguous() {
        // Player has 1, 4, 16 but not 2, 8.
        Predicate<String> op = checker(
                "sculpt.command.resolution.1", "sculpt.command.resolution.4",
                "sculpt.command.resolution.16");
        assertEquals(List.of("1", "4", "16"),
                SculptCommand.allowedGridSizes(op));
    }

    // ---------------------------------------------------------------
    //  user-facing command tree
    // ---------------------------------------------------------------

    @Test
    void primaryCommands_alwaysExposeHelp() {
        assertEquals(List.of("help"), SculptCommand.allowedPrimaryCommands(empty()));
    }

    @Test
    void primaryCommands_groupCanonicalPermissionsByUserTask() {
        Predicate<String> player = checker(
                "sculpt.command.resolution",
                "sculpt.command.preview",
                "sculpt.command.mode.on",
                "sculpt.command.fill.barrier",
                "sculpt.command.display.head",
                "sculpt.command.convert",
                "sculpt.command.replace",
                "sculpt.command.relight",
                "sculpt.command.tool.selector",
                "sculpt.command.blueprint.save",
                "sculpt.command.heads");

        assertEquals(List.of("help", "resolution", "preview", "mode", "fill",
                        "display", "convert", "replace", "relight", "tool",
                        "blueprint", "heads"),
                SculptCommand.allowedPrimaryCommands(player));
    }

    @Test
    void primaryCommands_rejectRemovedPermissionNodes() {
        Predicate<String> removed = checker(
                "sculptmode.disable",
                "sculptwand.selector",
                "sculptblueprint.command",
                "sculptheads.command");

        assertEquals(List.of("help"),
                SculptCommand.allowedPrimaryCommands(removed));
    }

    @Test
    void primaryCommands_exposeConvertOutsideAdmin() {
        assertEquals(List.of("help", "convert"),
                SculptCommand.allowedPrimaryCommands(checker(SculptPermissions.CONVERT)));
    }

    @Test
    void primaryCommands_exposeReplaceOutsideAdmin() {
        assertEquals(List.of("help", "replace"),
                SculptCommand.allowedPrimaryCommands(checker(SculptPermissions.REPLACE)));
    }

    @Test
    void primaryCommands_exposeRelightOutsideAdmin() {
        assertEquals(List.of("help", "relight"),
                SculptCommand.allowedPrimaryCommands(
                    checker(SculptPermissions.RELIGHT)));
    }

    @Test
    void primaryCommands_collapseMaintenancePermissionsUnderAdmin() {
        Predicate<String> admin = checker(
                "sculpt.command.admin.list",
                "sculpt.command.admin.status");

        assertEquals(List.of("help", "admin"),
                SculptCommand.allowedPrimaryCommands(admin));
    }
}
