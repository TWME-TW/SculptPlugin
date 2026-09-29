package dev.twme.sculpt.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class SculptPermissionManifestTest {

    private static final Path PLUGIN_YML = Path.of("src/main/resources/plugin.yml");

    @Test
    void resourceYamlFilesAreWellFormed() throws IOException {
        for (Path path : List.of(
            PLUGIN_YML,
            Path.of("src/main/resources/config.yml"),
            Path.of("src/main/resources/lang/en_us.yml"),
            Path.of("src/main/resources/lang/zh_tw.yml")
        )) {
            try (InputStream input = Files.newInputStream(path)) {
                assertNotNull(new Yaml().load(input), () -> path + " must contain YAML data");
            }
        }
    }

    @Test
    void manifestDeclaresOnlyCanonicalRootCommand() throws IOException {
        assertEquals(Set.of("sculpt"), commands().keySet());
    }

    @Test
    void manifestLoadsSqliteThroughPaper() throws IOException {
        assertEquals(List.of("org.xerial:sqlite-jdbc:${sqlite.version}"),
                manifest().get("libraries"));
    }

    @Test
    void manifestDeclaresOnlyCanonicalPermissionTree() throws IOException {
        final Set<String> expected = new java.util.HashSet<>(Set.of(
            SculptPermissions.COMMAND_ALL,
            SculptPermissions.EDIT,
            SculptPermissions.UNDO,
            SculptPermissions.resolution(1),
            SculptPermissions.resolution(2),
            SculptPermissions.resolution(4),
            SculptPermissions.resolution(8),
            SculptPermissions.resolution(16),
            SculptPermissions.RESOLUTION_ALL,
            SculptPermissions.FILL_BARRIER,
            SculptPermissions.FILL_SHULKER,
            SculptPermissions.FILL_NULL,
            SculptPermissions.FILL_ALL,
            SculptPermissions.DISPLAY_HEAD,
            SculptPermissions.DISPLAY_TEXTDISPLAY,
            SculptPermissions.DISPLAY_AUTO,
            SculptPermissions.DISPLAY_ALL,
            SculptPermissions.CONVERT,
            SculptPermissions.REPLACE,
            SculptPermissions.RELIGHT,
            SculptPermissions.BLUEPRINT_ALL,
            SculptPermissions.HEADS,
            SculptPermissions.ADMIN_LIST,
            SculptPermissions.ADMIN_TELEPORT,
            SculptPermissions.ADMIN_RELOAD,
            SculptPermissions.ADMIN_STATUS,
            SculptPermissions.ADMIN_ALL,
            SculptPermissions.EDITOR_ALL,
            SculptPermissions.BYPASS_REGION_PROTECTION));
        expected.addAll(SculptPermissions.BLUEPRINT_PERMISSIONS);
        expected.addAll(SculptPermissions.EDITOR_TOOLS);
        assertEquals(expected, permissions().keySet());
    }

    @Test
    void wildcardPermissionsExplicitlyExpandTheirChildren() throws IOException {
        final Map<String, Map<String, Object>> permissions = permissions();

        assertEquals(Set.of(
            SculptPermissions.EDIT,
            SculptPermissions.UNDO,
            SculptPermissions.RESOLUTION_ALL,
            SculptPermissions.FILL_ALL,
            SculptPermissions.DISPLAY_ALL,
            SculptPermissions.CONVERT,
            SculptPermissions.REPLACE,
            SculptPermissions.RELIGHT,
            SculptPermissions.BLUEPRINT_ALL,
            SculptPermissions.HEADS,
            SculptPermissions.ADMIN_ALL,
            SculptPermissions.EDITOR_ALL
        ), children(permissions, SculptPermissions.COMMAND_ALL));
        assertEquals(Set.of(
            SculptPermissions.resolution(1),
            SculptPermissions.resolution(2),
            SculptPermissions.resolution(4),
            SculptPermissions.resolution(8),
            SculptPermissions.resolution(16)
        ), children(permissions, SculptPermissions.RESOLUTION_ALL));
        assertEquals(Set.of(
            SculptPermissions.FILL_BARRIER,
            SculptPermissions.FILL_SHULKER,
            SculptPermissions.FILL_NULL
        ), children(permissions, SculptPermissions.FILL_ALL));
        assertEquals(Set.of(
            SculptPermissions.DISPLAY_HEAD,
            SculptPermissions.DISPLAY_TEXTDISPLAY,
            SculptPermissions.DISPLAY_AUTO
        ), children(permissions, SculptPermissions.DISPLAY_ALL));
        assertEquals(Set.copyOf(SculptPermissions.BLUEPRINT_PERMISSIONS),
            children(permissions, SculptPermissions.BLUEPRINT_ALL));
        assertEquals(Set.of(
            SculptPermissions.ADMIN_LIST,
            SculptPermissions.ADMIN_TELEPORT,
            SculptPermissions.ADMIN_RELOAD,
            SculptPermissions.ADMIN_STATUS
        ), children(permissions, SculptPermissions.ADMIN_ALL));
        assertEquals(Set.copyOf(SculptPermissions.EDITOR_TOOLS),
            children(permissions, SculptPermissions.EDITOR_ALL));
    }

    @Test
    void playerDefaultsRemainIntentional() throws IOException {
        final Map<String, Map<String, Object>> permissions = permissions();

        assertEquals(Boolean.TRUE, permissions.get(SculptPermissions.EDIT).get("default"));
        assertEquals(Boolean.TRUE, permissions.get(SculptPermissions.UNDO).get("default"));
        assertEquals(Boolean.TRUE, permissions.get(SculptPermissions.EDITOR_SCULPT).get("default"));
        assertEquals(Boolean.TRUE, permissions.get(SculptPermissions.EDITOR_PAINT).get("default"));
        for (String tool : List.of(SculptPermissions.EDITOR_BRUSH, SculptPermissions.EDITOR_SMOOTH,
                SculptPermissions.EDITOR_SELECT, SculptPermissions.EDITOR_TRANSFORM,
                SculptPermissions.EDITOR_SHAPE, SculptPermissions.EDITOR_BLUEPRINT)) {
            assertEquals("op", permissions.get(tool).get("default"), tool);
        }
        assertEquals(Boolean.TRUE,
            permissions.get(SculptPermissions.FILL_BARRIER).get("default"));
        assertEquals(Boolean.TRUE,
            permissions.get(SculptPermissions.FILL_SHULKER).get("default"));
        assertEquals(Boolean.TRUE,
            permissions.get(SculptPermissions.DISPLAY_HEAD).get("default"));
        assertEquals("op",
            permissions.get(SculptPermissions.DISPLAY_AUTO).get("default"));
        assertEquals("op", permissions.get(SculptPermissions.CONVERT).get("default"));
        assertEquals("op", permissions.get(
                SculptPermissions.BYPASS_REGION_PROTECTION).get("default"));
    }

    @Test
    void everyEditorToolHasAPermission() {
        for (dev.twme.sculpt.editor.ToolId tool : dev.twme.sculpt.editor.ToolId.values()) {
            if (tool.permission() != null) {
                org.junit.jupiter.api.Assertions.assertTrue(
                    SculptPermissions.EDITOR_TOOLS.contains(tool.permission()), tool.name());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> commands() throws IOException {
        return (Map<String, Map<String, Object>>) manifest().get("commands");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> permissions() throws IOException {
        return (Map<String, Map<String, Object>>) manifest().get("permissions");
    }

    private static Map<String, Object> manifest() throws IOException {
        try (InputStream input = Files.newInputStream(PLUGIN_YML)) {
            return new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> children(final Map<String, Map<String, Object>> permissions,
                                        final String parent) {
        return ((Map<String, Boolean>) permissions.get(parent).get("children")).keySet();
    }
}
