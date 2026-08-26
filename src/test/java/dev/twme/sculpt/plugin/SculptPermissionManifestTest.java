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
        assertEquals(Set.of(
            SculptPermissions.COMMAND_ALL,
            SculptPermissions.RESOLUTION,
            SculptPermissions.resolution(1),
            SculptPermissions.resolution(2),
            SculptPermissions.resolution(4),
            SculptPermissions.resolution(8),
            SculptPermissions.resolution(16),
            SculptPermissions.RESOLUTION_ALL,
            SculptPermissions.PREVIEW,
            SculptPermissions.CONVERT,
            SculptPermissions.REPLACE,
            SculptPermissions.RELIGHT,
            SculptPermissions.MODE_ON,
            SculptPermissions.MODE_OFF,
            SculptPermissions.MODE_ALL,
            SculptPermissions.FILL_BARRIER,
            SculptPermissions.FILL_SHULKER,
            SculptPermissions.FILL_NULL,
            SculptPermissions.FILL_ALL,
            SculptPermissions.DISPLAY_HEAD,
            SculptPermissions.DISPLAY_TEXTDISPLAY,
            SculptPermissions.DISPLAY_AUTO,
            SculptPermissions.DISPLAY_ALL,
            SculptPermissions.TOOL_SELECTOR,
            SculptPermissions.TOOL_BLUEPRINT,
            SculptPermissions.TOOL_ALL,
            SculptPermissions.BLUEPRINT_SAVE,
            SculptPermissions.BLUEPRINT_LIST,
            SculptPermissions.BLUEPRINT_DELETE,
            SculptPermissions.BLUEPRINT_RENAME,
            SculptPermissions.BLUEPRINT_DOWNLOAD,
            SculptPermissions.BLUEPRINT_BIND,
            SculptPermissions.BLUEPRINT_UNBIND,
            SculptPermissions.BLUEPRINT_GIVE,
            SculptPermissions.BLUEPRINT_SETTINGS,
            SculptPermissions.BLUEPRINT_PUBLISH,
            SculptPermissions.BLUEPRINT_UNPUBLISH,
            SculptPermissions.BLUEPRINT_EXPORT,
            SculptPermissions.BLUEPRINT_IMPORT,
            SculptPermissions.BLUEPRINT_ALL,
            SculptPermissions.HEADS,
            SculptPermissions.ADMIN_LIST,
            SculptPermissions.ADMIN_TELEPORT,
            SculptPermissions.ADMIN_RELOAD,
            SculptPermissions.ADMIN_STATUS,
            SculptPermissions.ADMIN_ALL,
            SculptPermissions.USE_SELECTOR,
            SculptPermissions.USE_PREVIEW_AUTO,
            SculptPermissions.USE_ALL,
            SculptPermissions.BYPASS_REGION_PROTECTION
        ), permissions().keySet());
    }

    @Test
    void wildcardPermissionsExplicitlyExpandTheirChildren() throws IOException {
        final Map<String, Map<String, Object>> permissions = permissions();

        assertEquals(Set.of(
            SculptPermissions.RESOLUTION_ALL,
            SculptPermissions.PREVIEW,
            SculptPermissions.CONVERT,
            SculptPermissions.REPLACE,
            SculptPermissions.RELIGHT,
            SculptPermissions.MODE_ALL,
            SculptPermissions.FILL_ALL,
            SculptPermissions.DISPLAY_ALL,
            SculptPermissions.TOOL_ALL,
            SculptPermissions.BLUEPRINT_ALL,
            SculptPermissions.HEADS,
            SculptPermissions.ADMIN_ALL
        ), children(permissions, SculptPermissions.COMMAND_ALL));
        assertEquals(Set.of(
            SculptPermissions.RESOLUTION,
            SculptPermissions.resolution(1),
            SculptPermissions.resolution(2),
            SculptPermissions.resolution(4),
            SculptPermissions.resolution(8),
            SculptPermissions.resolution(16)
        ), children(permissions, SculptPermissions.RESOLUTION_ALL));
        assertEquals(Set.of(
            SculptPermissions.MODE_ON,
            SculptPermissions.MODE_OFF
        ), children(permissions, SculptPermissions.MODE_ALL));
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
        assertEquals(Set.of(
            SculptPermissions.TOOL_SELECTOR,
            SculptPermissions.TOOL_BLUEPRINT
        ), children(permissions, SculptPermissions.TOOL_ALL));
        assertEquals(Set.of(
            SculptPermissions.BLUEPRINT_SAVE,
            SculptPermissions.BLUEPRINT_LIST,
            SculptPermissions.BLUEPRINT_DELETE,
            SculptPermissions.BLUEPRINT_RENAME,
            SculptPermissions.BLUEPRINT_DOWNLOAD,
            SculptPermissions.BLUEPRINT_BIND,
            SculptPermissions.BLUEPRINT_UNBIND,
            SculptPermissions.BLUEPRINT_GIVE,
            SculptPermissions.BLUEPRINT_SETTINGS,
            SculptPermissions.BLUEPRINT_PUBLISH,
            SculptPermissions.BLUEPRINT_UNPUBLISH,
            SculptPermissions.BLUEPRINT_EXPORT,
            SculptPermissions.BLUEPRINT_IMPORT
        ), children(permissions, SculptPermissions.BLUEPRINT_ALL));
        assertEquals(Set.of(
            SculptPermissions.ADMIN_LIST,
            SculptPermissions.ADMIN_TELEPORT,
            SculptPermissions.ADMIN_RELOAD,
            SculptPermissions.ADMIN_STATUS
        ), children(permissions, SculptPermissions.ADMIN_ALL));
        assertEquals(Set.of(
            SculptPermissions.USE_SELECTOR,
            SculptPermissions.USE_PREVIEW_AUTO
        ), children(permissions, SculptPermissions.USE_ALL));
    }

    @Test
    void playerDefaultsRemainIntentional() throws IOException {
        final Map<String, Map<String, Object>> permissions = permissions();

        assertEquals(Boolean.TRUE, permissions.get(SculptPermissions.RESOLUTION).get("default"));
        assertEquals(Boolean.FALSE, permissions.get(SculptPermissions.PREVIEW).get("default"));
        assertEquals(Boolean.TRUE, permissions.get(SculptPermissions.MODE_ON).get("default"));
        assertEquals(Boolean.TRUE, permissions.get(SculptPermissions.MODE_OFF).get("default"));
        assertEquals(Boolean.TRUE,
            permissions.get(SculptPermissions.FILL_BARRIER).get("default"));
        assertEquals(Boolean.TRUE,
            permissions.get(SculptPermissions.FILL_SHULKER).get("default"));
        assertEquals(Boolean.TRUE,
            permissions.get(SculptPermissions.DISPLAY_HEAD).get("default"));
        assertEquals("op",
            permissions.get(SculptPermissions.DISPLAY_AUTO).get("default"));
        assertEquals(Boolean.TRUE, permissions.get(SculptPermissions.USE_SELECTOR).get("default"));
        assertEquals(Boolean.FALSE,
            permissions.get(SculptPermissions.USE_PREVIEW_AUTO).get("default"));
    }

    @Test
    void convertPreservesItsOperatorDefault() throws IOException {
        assertEquals("op", permissions().get(SculptPermissions.CONVERT).get("default"));
    }

    @Test
    void regionProtectionBypassDefaultsToOperators() throws IOException {
        assertEquals("op", permissions().get(
                SculptPermissions.BYPASS_REGION_PROTECTION).get("default"));
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
