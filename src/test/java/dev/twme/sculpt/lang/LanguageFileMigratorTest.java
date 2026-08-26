package dev.twme.sculpt.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageFileMigratorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesRenamedSectionAndMergesMissingMessages() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("command.sculpt.hover.enabled", "custom enabled");
        current.set("command.sculpt.hover.lore", List.of("custom lore"));
        YamlConfiguration defaults = defaults();

        LanguageFileMigrator.MigrationResult result =
            LanguageFileMigrator.migrateAndMerge(current, defaults);

        assertTrue(result.changed());
        assertTrue(result.migrated());
        assertFalse(result.newerVersion());
        assertEquals(3, current.getInt("languageVersion"));
        assertEquals("custom enabled",
            current.getString("command.sculpt.preview.enabled"));
        assertEquals(List.of("custom lore"),
            current.getStringList("command.sculpt.preview.lore"));
        assertEquals("default disabled",
            current.getString("command.sculpt.preview.disabled"));
        assertNull(current.getConfigurationSection("command.sculpt.hover"));

        LanguageFileMigrator.MigrationResult repeated =
            LanguageFileMigrator.migrateAndMerge(current, defaults);
        assertFalse(repeated.changed());
        assertFalse(repeated.migrated());
    }

    @Test
    void currentPathWinsWhenLegacyAndCurrentValuesBothExist() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("command.sculpt.hover.enabled", "legacy custom");
        current.set("command.sculpt.preview.enabled", "current custom");

        LanguageFileMigrator.migrateAndMerge(current, defaults());

        assertEquals("current custom",
            current.getString("command.sculpt.preview.enabled"));
        assertNull(current.getConfigurationSection("command.sculpt.hover"));
    }

    @Test
    void currentVersionOnlyMergesMissingMessages() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("languageVersion", 3);
        current.set("command.sculpt.preview.enabled", "custom enabled");

        LanguageFileMigrator.MigrationResult result =
            LanguageFileMigrator.migrateAndMerge(current, defaults());

        assertTrue(result.changed());
        assertFalse(result.migrated());
        assertEquals("custom enabled",
            current.getString("command.sculpt.preview.enabled"));
        assertEquals("default disabled",
            current.getString("command.sculpt.preview.disabled"));
    }

    @Test
    void versionTwoRemovesChiselMessagesAndRefreshesToolUsage() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("languageVersion", 1);
        current.set("sculpt_tool.name", "custom chisel");
        current.set("sculptwand.chisel.self", "custom received");
        current.set("sculptwand.usage", "old chisel usage");
        current.set("sculptwand.unknown", "old chisel choices");
        current.set("wandtool.cleared", "custom clear");
        YamlConfiguration defaults = defaults();
        defaults.set("sculptwand.usage", "new selector usage");
        defaults.set("sculptwand.unknown", "new selector choices");

        LanguageFileMigrator.MigrationResult result =
            LanguageFileMigrator.migrateAndMerge(current, defaults);

        assertTrue(result.migrated());
        assertEquals(3, current.getInt("languageVersion"));
        assertNull(current.getConfigurationSection("sculpt_tool"));
        assertNull(current.getConfigurationSection("sculptwand.chisel"));
        assertEquals("new selector usage", current.getString("sculptwand.usage"));
        assertEquals("new selector choices", current.getString("sculptwand.unknown"));
        assertEquals("custom clear", current.getString("wandtool.cleared"));
    }

    @Test
    void versionThreeRefreshesPauseShortcutMessages() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("languageVersion", 2);
        current.set("sculptmode.enabled", "old F double shortcut");
        current.set("sculptcontrols.paused", "old pause message");
        current.set("sculptcontrols.resumed", "old resume message");
        current.set("sculptcontrols.paused_reminder", "old reminder");
        current.set("general.no_permission", "custom permission message");
        YamlConfiguration defaults = defaults();
        defaults.set("sculptmode.enabled", "new Shift+Q shortcut");
        defaults.set("sculptcontrols.paused", "new pause message");
        defaults.set("sculptcontrols.resumed", "new resume message");

        LanguageFileMigrator.MigrationResult result =
            LanguageFileMigrator.migrateAndMerge(current, defaults);

        assertTrue(result.migrated());
        assertEquals(3, current.getInt("languageVersion"));
        assertEquals("new Shift+Q shortcut",
            current.getString("sculptmode.enabled"));
        assertEquals("new pause message",
            current.getString("sculptcontrols.paused"));
        assertEquals("new resume message",
            current.getString("sculptcontrols.resumed"));
        assertNull(current.getString("sculptcontrols.paused_reminder"));
        assertEquals("custom permission message",
            current.getString("general.no_permission"));
    }

    @Test
    void newerLanguageSchemaIsLeftUnchanged() {
        YamlConfiguration current = new YamlConfiguration();
        current.set("languageVersion", 99);
        current.set("command.sculpt.hover.enabled", "future value");

        LanguageFileMigrator.MigrationResult result =
            LanguageFileMigrator.migrateAndMerge(current, defaults());

        assertFalse(result.changed());
        assertFalse(result.migrated());
        assertTrue(result.newerVersion());
        assertEquals(99, current.getInt("languageVersion"));
        assertEquals("future value",
            current.getString("command.sculpt.hover.enabled"));
        assertFalse(current.getValues(false).containsKey("new_message"));
    }

    @Test
    void legacyDottedCustomValueIsNotShadowedByBundledDefault() throws Exception {
        YamlConfiguration current = new YamlConfiguration();
        current.loadFromString("""
            languageVersion: 3
            command.sculpt.blueprint:
              existing: custom translation
            """);
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.loadFromString("""
            languageVersion: 3
            command.sculpt.blueprint:
              existing: bundled translation
              added: newly bundled translation
            """);

        LanguageFileMigrator.MigrationResult result =
            LanguageFileMigrator.migrateAndMerge(current, defaults);

        assertTrue(result.changed());
        assertEquals("custom translation",
            LanguageManager.lookupString(current,
                "command.sculpt.blueprint.existing"));
        assertEquals("newly bundled translation",
            LanguageManager.lookupString(current,
                "command.sculpt.blueprint.added"));
    }

    @Test
    void updatedLanguageIsSavedThroughAtomicReplacement() throws Exception {
        Path destination = temporaryDirectory.resolve("en_us.yml");
        Files.writeString(destination, "languageVersion: 0\n");
        YamlConfiguration updated = new YamlConfiguration();
        updated.set("languageVersion", 3);
        updated.set("general.message", "updated");

        LanguageManager.saveLanguageAtomically(destination, updated);

        YamlConfiguration reloaded = YamlConfiguration.loadConfiguration(
            destination.toFile());
        assertEquals(3, reloaded.getInt("languageVersion"));
        assertEquals("updated", reloaded.getString("general.message"));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of("en_us.yml"),
                files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    private static YamlConfiguration defaults() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("languageVersion", 3);
        defaults.set("command.sculpt.preview.enabled", "default enabled");
        defaults.set("command.sculpt.preview.disabled", "default disabled");
        defaults.set("new_message", "new default");
        return defaults;
    }
}
