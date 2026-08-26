package dev.twme.sculpt.lang;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import dev.twme.sculpt.Sculpt;

/**
 * Manages language translations for the Sculpt plugin.
 *
 * <p>Supports automatic player locale detection, per-player overrides, and
 * fallback to a configurable default language.  Translation keys use
 * MiniMessage format so colour codes and hover/click events are embedded
 * in the YAML values, not in the calling code.
 *
 * <p>Based on the same pattern used by WorldEditDisplay.
 */
public final class LanguageManager {

    private static final String[] BUNDLED_LANGUAGES = {"en_us", "zh_tw"};

    private final Sculpt plugin;
    private final Map<String, YamlConfiguration> languages = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerLanguages = new ConcurrentHashMap<>();
    private volatile String defaultLanguage = "en_us";
    private volatile boolean autoDetect = true;

    public LanguageManager(final Sculpt plugin) {
        this.plugin = plugin;
    }

    // ========================================================================
    //  Lifecycle
    // ========================================================================

    /**
     * Load all bundled language files from disk (copying from JAR on first
     * run), migrate and persist older schemas, then read the configured
     * default language and auto-detect setting.
     */
    public void initialize() {
        final File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        // Copy bundled files on first run. Existing files are migrated and
        // receive missing bundled messages without overwriting custom text.
        for (final String lang : BUNDLED_LANGUAGES) {
            saveDefaultLanguage(lang);
            loadLanguage(lang);
        }

        this.defaultLanguage = plugin.getConfig().getString("language.default", "en_us");
        this.autoDetect = plugin.getConfig().getBoolean("language.autoDetect", true);

        plugin.getLogger().log(Level.INFO,
                "[Sculpt] language system initialized (default={0}, autoDetect={1}, loaded={2})",
                new Object[]{defaultLanguage, autoDetect, languages.size()});
    }

    /**
     * Reload all language files from disk. Called on {@code /sculpt admin reload}.
     */
    public void reload() {
        languages.clear();
        initialize();
    }

    // ========================================================================
    //  Internal: file I/O
    // ========================================================================

    private void saveDefaultLanguage(final String lang) {
        final File langFile = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");
        if (langFile.exists()) return;

        try (final InputStream in = plugin.getResource("lang/" + lang + ".yml")) {
            if (in != null) {
                Files.copy(in, langFile.toPath());
            }
        } catch (final IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "[Sculpt] failed to save default language file: " + lang, e);
        }
    }

    private void loadLanguage(final String lang) {
        final File langFile = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");
        if (!langFile.exists()) {
            plugin.getLogger().log(Level.WARNING,
                    "[Sculpt] language file not found: {0}", lang);
            return;
        }

        try {
            final YamlConfiguration config = new YamlConfiguration();
            config.load(langFile);
            final YamlConfiguration defaults = loadBundledLanguage(lang);
            final LanguageFileMigrator.MigrationResult result =
                LanguageFileMigrator.migrateAndMerge(config, defaults);

            if (result.newerVersion()) {
                plugin.getLogger().warning("[Sculpt] language file " + lang
                    + ".yml uses newer schema version "
                    + config.getInt(LanguageFileMigrator.VERSION_PATH)
                    + "; leaving it unchanged");
            } else if (result.changed()) {
                try {
                    saveLanguageAtomically(langFile.toPath(), result.configuration());
                    plugin.getLogger().info(result.migrated()
                        ? "[Sculpt] migrated " + lang + ".yml to language schema version "
                            + LanguageFileMigrator.CURRENT_VERSION
                            + " and merged new messages"
                        : "[Sculpt] merged new messages into " + lang + ".yml");
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING,
                        "[Sculpt] failed to save updated language file: " + lang, e);
                }
            }

            languages.put(lang, result.configuration());
        } catch (final Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "[Sculpt] failed to load language file: " + lang, e);
        }
    }

    private YamlConfiguration loadBundledLanguage(String lang)
            throws IOException, InvalidConfigurationException {
        try (InputStream in = plugin.getResource("lang/" + lang + ".yml")) {
            if (in == null) {
                throw new IOException("Bundled language file not found: " + lang);
            }
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return defaults;
        }
    }

    static void saveLanguageAtomically(
            Path destination, YamlConfiguration configuration) throws IOException {
        Path temporary = Files.createTempFile(
            destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            configuration.save(temporary.toFile());
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // ========================================================================
    //  Player language resolution
    // ========================================================================

    /**
     * Get the effective language for a player.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit per-player override (set via API — CLI command TBD)</li>
     *   <li>Client locale auto-detection (if {@code language.autoDetect} is enabled)</li>
     *   <li>Configured default language</li>
     * </ol>
     */
    public String getPlayerLanguage(final Player player) {
        if (!autoDetect) return defaultLanguage;
        return playerLanguages.getOrDefault(player.getUniqueId(), detectClientLanguage(player));
    }

    /**
     * Set an explicit per-player language override.
     *
     * @param uuid     the player's UUID
     * @param language the language code (e.g. {@code "en_us"}, {@code "zh_tw"})
     */
    public void setPlayerLanguage(final UUID uuid, final String language) {
        if (languages.containsKey(language)) {
            playerLanguages.put(uuid, language);
        }
    }

    /**
     * Remove a per-player language override, reverting to auto-detection.
     */
    public void removePlayerLanguage(final UUID uuid) {
        playerLanguages.remove(uuid);
    }

    private String detectClientLanguage(final Player player) {
        try {
            final String clientLocale = player.getLocale().toLowerCase().replace("-", "_");

            // Exact match (e.g. "en_us" or "zh_tw")
            if (languages.containsKey(clientLocale)) return clientLocale;

            // Prefix match (e.g. "zh" matches "zh_tw" or "zh_cn")
            final String langCode = clientLocale.split("_")[0];
            for (final String lang : languages.keySet()) {
                if (lang.startsWith(langCode)) return lang;
            }
        } catch (final Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "[Sculpt] failed to detect player language", e);
        }
        return defaultLanguage;
    }

    // ========================================================================
    //  Message lookup
    // ========================================================================

    /**
     * Look up a translated message for a player, using their detected or
     * overridden language.
     *
     * @param player the target player
     * @param key    the YAML path (e.g. {@code "command.sculpt.test.spawned"})
     * @param args   optional indexed placeholder ({@code {0}}, {@code {1}}) values
     * @return the translated message in MiniMessage format, or the raw key
     *         if no translation is found
     */
    public String getMessage(final Player player, final String key, final Object... args) {
        return getMessage(getPlayerLanguage(player), key, args);
    }

    /**
     * Look up a translated message for a specific language code.
     *
     * @param lang the language code (e.g. {@code "en_us"})
     * @param key  the YAML path
     * @param args optional indexed placeholder ({@code {0}}, {@code {1}}) values
     * @return the translated message, or the raw key if missing
     */
    public String getMessage(final String lang, final String key, final Object... args) {
        YamlConfiguration config = languages.get(lang);
        if (config == null) {
            config = languages.get(defaultLanguage);
        }
        if (config == null) return key;

        String message = lookupString(config, key);

        // Fallback chain: requested language → English (en_us) → default language → raw key
        if (message == null) {
            if (!"en_us".equals(lang)) {
                final YamlConfiguration enConfig = languages.get("en_us");
                if (enConfig != null) {
                    message = lookupString(enConfig, key);
                }
            }
        }
        if (message == null) {
            if (!lang.equals(defaultLanguage) && !"en_us".equals(defaultLanguage)) {
                final YamlConfiguration defConfig = languages.get(defaultLanguage);
                if (defConfig != null) {
                    message = lookupString(defConfig, key);
                }
            }
        }
        if (message == null) return key;
        if (args.length > 0) {
            message = formatMessage(message, args);
        }
        return message;
    }

    /**
     * Resolve both canonical nested YAML paths and legacy top-level sections
     * whose names contain dots, such as {@code command.sculpt.blueprint}.
     */
    static Object lookupValue(final Configuration config, final String key) {
        Object value = lookupOwnValue(config, key);
        if (value != null) return value;
        final Configuration defaults = config.getDefaults();
        return defaults != null ? lookupValue(defaults, key) : null;
    }

    private static Object lookupOwnValue(final ConfigurationSection config, final String key) {
        final Object direct = lookupOwnPath(config, key);
        if (direct != null) return direct;

        int separator = key.lastIndexOf('.');
        while (separator > 0) {
            final String sectionName = key.substring(0, separator);
            final Object sectionValue = config.getValues(false).get(sectionName);
            if (sectionValue instanceof ConfigurationSection section) {
                final Object nested = lookupOwnPath(section, key.substring(separator + 1));
                if (nested != null) return nested;
            }
            separator = key.lastIndexOf('.', separator - 1);
        }
        return null;
    }

    private static Object lookupOwnPath(final ConfigurationSection config, final String path) {
        Object current = config;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof ConfigurationSection section)) return null;
            current = section.getValues(false).get(segment);
            if (current == null) return null;
        }
        return current;
    }

    static String lookupString(final Configuration config, final String key) {
        final Object value = lookupValue(config, key);
        return value instanceof String string ? string : null;
    }

    static String formatMessage(final String message, final Object... args) {
        String formatted = message;
        for (int i = 0; i < args.length; i++) {
            formatted = formatted.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return formatted;
    }

    // ========================================================================
    //  Queries
    // ========================================================================

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    /**
     * Look up a translated string list for a player (e.g. lore lines).
     * Falls back to default language if the player's language is missing
     * the key, then returns the key as a single-element list.
     *
     * @param player the target player
     * @param key    the YAML path to a list value
     * @return the translated list, never null
     */
    public List<String> getStringList(final Player player, final String key) {
        final String lang = getPlayerLanguage(player);
        YamlConfiguration config = languages.get(lang);
        if (config == null) {
            config = languages.get(defaultLanguage);
        }
        if (config != null) {
            final List<String> translated = lookupStringList(config, key);
            if (translated != null) return translated;
        }

        // Fallback chain: requested language → English (en_us) → default language → raw key
        if (!"en_us".equals(lang)) {
            final YamlConfiguration enConfig = languages.get("en_us");
            if (enConfig != null) {
                final List<String> translated = lookupStringList(enConfig, key);
                if (translated != null) return translated;
            }
        }
        if (!lang.equals(defaultLanguage) && !"en_us".equals(defaultLanguage)) {
            final YamlConfiguration defConfig = languages.get(defaultLanguage);
            if (defConfig != null) {
                final List<String> translated = lookupStringList(defConfig, key);
                if (translated != null) return translated;
            }
        }
        return Collections.singletonList(key);
    }

    private static List<String> lookupStringList(final Configuration config, final String key) {
        final Object value = lookupValue(config, key);
        if (!(value instanceof List<?> list)) return null;
        return list.stream().map(String::valueOf).toList();
    }

    public Set<String> getAvailableLanguages() {
        return languages.keySet();
    }

    public boolean isLanguageAvailable(final String lang) {
        return languages.containsKey(lang);
    }
}
