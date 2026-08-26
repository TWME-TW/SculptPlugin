package dev.twme.sculpt.lang;

import org.bukkit.configuration.file.YamlConfiguration;

import dev.twme.sculpt.util.YamlMigrationSupport;

/** Applies versioned language-key migrations and persists newly bundled messages. */
public final class LanguageFileMigrator {

    public static final int CURRENT_VERSION = 3;
    public static final String VERSION_PATH = "languageVersion";

    private LanguageFileMigrator() {}

    public static MigrationResult migrateAndMerge(
            YamlConfiguration current, YamlConfiguration defaults) {
        int sourceVersion = current.contains(VERSION_PATH, true)
            ? current.getInt(VERSION_PATH) : 0;
        if (sourceVersion > CURRENT_VERSION) {
            current.setDefaults(defaults);
            return new MigrationResult(current, false, false, true);
        }

        boolean changed = false;
        boolean migrated = false;

        if (sourceVersion < 1) {
            changed |= YamlMigrationSupport.move(
                current, "command.sculpt.hover", "command.sculpt.preview");
            current.set(VERSION_PATH, 1);
            changed = true;
            migrated = true;
        }

        if (sourceVersion < 2) {
            // The chisel and its command were removed. Reset messages whose
            // text embedded the old subcommand so current bundled wording is
            // merged back in, while leaving unrelated custom text untouched.
            changed |= remove(current, "sculpt_tool");
            changed |= remove(current, "sculptwand.chisel");
            changed |= remove(current, "sculptwand.usage");
            changed |= remove(current, "sculptwand.unknown");
            current.set(VERSION_PATH, 2);
            changed = true;
            migrated = true;
        }

        if (sourceVersion < 3) {
            // Contextual controls now use Shift+Q for pause/resume instead of
            // F×2. Reset shortcut-bearing bundled messages while preserving
            // every unrelated custom translation.
            changed |= remove(current, "sculptmode.enabled");
            changed |= remove(current, "sculptcontrols.paused");
            changed |= remove(current, "sculptcontrols.resumed");
            changed |= remove(current, "sculptcontrols.paused_reminder");
            current.set(VERSION_PATH, 3);
            changed = true;
            migrated = true;
        }

        changed |= YamlMigrationSupport.mergeMissingLeaves(current, defaults);
        current.setDefaults(defaults);
        return new MigrationResult(current, changed, migrated, false);
    }

    private static boolean remove(
            final YamlConfiguration configuration,
            final String path) {
        if (!configuration.contains(path, true)) return false;
        configuration.set(path, null);
        return true;
    }

    public record MigrationResult(
        YamlConfiguration configuration,
        boolean changed,
        boolean migrated,
        boolean newerVersion
    ) {}
}
