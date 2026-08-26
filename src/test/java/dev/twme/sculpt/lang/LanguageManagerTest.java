package dev.twme.sculpt.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LanguageManagerTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    private static final List<String> REQUIRED_MESSAGES = List.of(
        "command.sculpt.convert.usage",
        "command.sculpt.replace.usage",
        "command.sculpt.replace.out_of_bounds",
        "command.sculpt.replace.too_many_leaves",
        "command.sculpt.relight.completed",
        "command.sculpt.preview.enabled",
        "command.sculpt.blueprint.save.usage",
        "command.sculpt.blueprint.select.success",
        "command.sculpt.blueprint.select.not_sculpt",
        "command.sculpt.blueprint.folia_cross_region",
        "command.sculpt.blueprint.publish.network_error",
        "command.sculpt.blueprint.publish.key_saved",
        "command.sculpt.blueprint.unpublish.success",
        "wand_tool.name"
    );

    @Test
    void bundledLanguagesResolveNestedAndLegacyDottedSections() {
        for (String language : List.of("en_us", "zh_tw")) {
            YamlConfiguration config = load(language);
            for (String key : REQUIRED_MESSAGES) {
                assertNotNull(LanguageManager.lookupString(config, key),
                    () -> language + " is missing " + key);
            }
            assertInstanceOf(List.class,
                LanguageManager.lookupValue(
                    config, "command.sculpt.blueprint.bind.item_lore"));
            assertEquals(LanguageFileMigrator.CURRENT_VERSION,
                config.getInt(LanguageFileMigrator.VERSION_PATH));
            assertNull(config.getConfigurationSection("command.sculpt.hover"));
        }
    }

    @Test
    void legacyDottedKeysCanBeReadFromBundledDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.setDefaults(load("en_us"));

        assertNotNull(LanguageManager.lookupString(
            config, "command.sculpt.blueprint.paste.success"));
    }

    @Test
    void onDiskLegacyValueTakesPriorityOverNestedDefault() {
        YamlConfiguration config = load("en_us");
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("command.sculpt.blueprint.paste.success", "default value");
        config.setDefaults(defaults);

        assertEquals("<#5FBA6B>Blueprint pasted: <white>{0}",
            LanguageManager.lookupString(
                config, "command.sculpt.blueprint.paste.success"));
    }

    @Test
    void placeholdersAreNotBrokenByNaturalLanguageApostrophes() {
        assertEquals("You don't have permission to use grid size 4.",
            LanguageManager.formatMessage(
                "You don't have permission to use grid size {0}.", 4));
    }

    @Test
    void bundledLanguagesExposeTheSameMessageCatalog() {
        Set<String> english = messageKeys(load("en_us"));
        Set<String> traditionalChinese = messageKeys(load("zh_tw"));
        assertEquals(Set.of(), difference(english, traditionalChinese),
            "zh_tw is missing keys from en_us");
        assertEquals(Set.of(), difference(traditionalChinese, english),
            "en_us is missing keys from zh_tw");
    }

    @Test
    void translationsRequireTheSamePlaceholders() {
        YamlConfiguration english = load("en_us");
        YamlConfiguration traditionalChinese = load("zh_tw");
        for (String key : messageKeys(english)) {
            assertEquals(
                placeholders(LanguageManager.lookupValue(english, key)),
                placeholders(LanguageManager.lookupValue(traditionalChinese, key)),
                () -> "placeholder mismatch for " + key);
        }
    }

    private static Set<String> placeholders(Object value) {
        Set<String> placeholders = new HashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(String.valueOf(value));
        while (matcher.find()) placeholders.add(matcher.group());
        return placeholders;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> difference = new HashSet<>(left);
        difference.removeAll(right);
        return difference;
    }

    private static Set<String> messageKeys(ConfigurationSection section) {
        Set<String> keys = new HashSet<>();
        collectMessageKeys(section, "", keys);
        return keys;
    }

    private static void collectMessageKeys(ConfigurationSection section, String prefix,
                                           Set<String> keys) {
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof ConfigurationSection child) {
                collectMessageKeys(child, key, keys);
            } else {
                keys.add(key);
            }
        }
    }

    private static YamlConfiguration load(String language) {
        return YamlConfiguration.loadConfiguration(
            new File("src/main/resources/lang/" + language + ".yml"));
    }
}
