package dev.twme.sculpt.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class BlueprintCommandPermissionTest {

    @Test
    void noPermissionsExposeNoOperations() {
        var commands = BlueprintCommand.allowedSubcommands(Set.<String>of()::contains);

        assertTrue(commands.isEmpty());
    }

    @Test
    void operationPermissionsExposeOnlyTheirMatchingCommands() {
        Set<String> permissions = Set.of(
            "sculpt.command.blueprint.save",
            "sculpt.command.blueprint.list",
            "sculpt.command.blueprint.publish",
            "sculpt.command.blueprint.unpublish",
            "sculpt.command.blueprint.import");

        var commands = BlueprintCommand.allowedSubcommands(permissions::contains);

        assertEquals(List.of("save", "list", "import", "publish", "unpublish"), commands);
    }

    @Test
    void removedPermissionsAreNotAccepted() {
        Set<String> permissions = Set.of(
            "sculpt.blueprint.publish",
            "sculptblueprint.export",
            "sculptblueprint.import");

        var commands = BlueprintCommand.allowedSubcommands(permissions::contains);

        assertTrue(commands.isEmpty());
    }
}
