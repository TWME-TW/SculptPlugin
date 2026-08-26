package dev.twme.sculpt.util;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Shared primitives for versioned YAML configuration and language migrations. */
public final class YamlMigrationSupport {

    private YamlMigrationSupport() {}

    /** Move a value or section while preserving values already present at the new path. */
    public static boolean move(FileConfiguration config, String oldPath, String newPath) {
        if (!config.contains(oldPath, true)) return false;

        Object oldValue = config.get(oldPath);
        if (oldValue instanceof ConfigurationSection oldSection) {
            copyMissingLeaves(config, oldSection, newPath);
        } else if (!config.contains(newPath, true)) {
            config.set(newPath, oldValue);
        }
        config.set(oldPath, null);
        return true;
    }

    /** Merge missing leaf values from bundled defaults without overwriting user values. */
    public static boolean mergeMissingLeaves(
            FileConfiguration current, ConfigurationSection defaults) {
        Set<String> existingPaths = new HashSet<>();
        collectLeafPaths(current, "", existingPaths);
        return mergeMissingLeaves(current, defaults, "", existingPaths);
    }

    /** Remove a section only when migration left it empty. */
    public static boolean removeEmptySection(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null || !section.getKeys(false).isEmpty()) return false;
        config.set(path, null);
        return true;
    }

    private static void copyMissingLeaves(
            FileConfiguration target, ConfigurationSection source, String targetPath) {
        for (String key : source.getKeys(false)) {
            Object value = source.get(key);
            String destination = targetPath + "." + key;
            if (value instanceof ConfigurationSection child) {
                copyMissingLeaves(target, child, destination);
            } else if (!target.contains(destination, true)) {
                target.set(destination, value);
            }
        }
    }

    private static boolean mergeMissingLeaves(
            FileConfiguration target, ConfigurationSection defaults, String prefix,
            Set<String> existingPaths) {
        boolean changed = false;
        for (Map.Entry<String, Object> entry : defaults.getValues(false).entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof ConfigurationSection child) {
                changed |= mergeMissingLeaves(target, child, path, existingPaths);
            } else if (!existingPaths.contains(path)) {
                target.set(path, entry.getValue());
                existingPaths.add(path);
                changed = true;
            }
        }
        return changed;
    }

    private static void collectLeafPaths(
            ConfigurationSection section, String prefix, Set<String> paths) {
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof ConfigurationSection child) {
                collectLeafPaths(child, path, paths);
            } else {
                paths.add(path);
            }
        }
    }
}
