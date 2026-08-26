package dev.twme.sculpt.util;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import dev.twme.sculpt.lang.LanguageManager;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Central utility for sending MiniMessage-formatted messages, action bars,
 * and translated messages.
 *
 * <p>Sculpt runs only on Paper (server implementations that implement
 * {@link net.kyori.adventure.audience.Audience} on {@link CommandSender}),
 * so we use the native Adventure API directly without Spigot fallback
 * detection.
 */
public final class MessageUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static LanguageManager languageManager;

    private MessageUtil() {
    }

    /**
     * Initialise the language manager reference.  Called once from
     * {@link dev.twme.sculpt.Sculpt#onEnable()} after the
     * {@link LanguageManager} has been initialised.
     */
    public static void init(final LanguageManager langManager) {
        languageManager = langManager;
    }

    /**
     * Send a MiniMessage-formatted string to a command sender.
     *
     * @param sender  the target (player or console)
     * @param message the message in MiniMessage format (e.g. {@code "<red>Error: <white>something"})
     */
    public static void sendMessage(final CommandSender sender, final String message) {
        sender.sendMessage(MINI_MESSAGE.deserialize(message));
    }

    /**
     * Send a MiniMessage-formatted string to a player.
     *
     * @param player  the target player
     * @param message the message in MiniMessage format
     */
    public static void sendMessage(final Player player, final String message) {
        player.sendMessage(MINI_MESSAGE.deserialize(message));
    }

    /**
     * Send a MiniMessage-formatted action bar message to a player.
     *
     * @param player  the target player
     * @param message the message in MiniMessage format
     */
    public static void sendActionBar(final Player player, final String message) {
        player.sendActionBar(MINI_MESSAGE.deserialize(message));
    }

    // ========================================================================
    //  Translated messages (via LanguageManager)
    // ========================================================================

    /**
     * Look up a translated message for the given player and send it.
     *
     * @param player the target player
     * @param key    the translation key (YAML path, e.g. {@code "command.sculpt.test.spawned"})
     * @param args   optional indexed placeholder ({@code {0}}, {@code {1}}) values
     */
    public static void sendTranslated(final Player player, final String key, final Object... args) {
        if (languageManager == null) {
            sendMessage(player, key);
            return;
        }
        sendMessage(player, languageManager.getMessage(player, key, args));
    }

    /**
     * Look up a translated message for the sender and send it.
     *
     * <p>If the sender is a {@link Player}, uses per-player language
     * resolution; otherwise uses the default language.
     *
     * @param sender the target (player or console)
     * @param key    the translation key
     * @param args   optional format arguments
     */
    public static void sendTranslated(final CommandSender sender, final String key, final Object... args) {
        if (sender instanceof Player player) {
            sendTranslated(player, key, args);
        } else if (languageManager != null) {
            sendMessage(sender, languageManager.getMessage(
                    languageManager.getDefaultLanguage(), key, args));
        } else {
            sendMessage(sender, key);
        }
    }

    /**
     * Look up a translated action bar message for the given player and send it.
     *
     * @param player the target player
     * @param key    the translation key
     * @param args   optional format arguments
     */
    public static void sendTranslatedActionBar(final Player player, final String key, final Object... args) {
        if (languageManager == null) {
            sendActionBar(player, key);
            return;
        }
        sendActionBar(player, languageManager.getMessage(player, key, args));
    }

    /** Default number of items per page. */
    public static final int PAGE_SIZE = 10;

    /**
     * Send a pagination bar (◀ Page X/Y ▶) with clickable MiniMessage buttons.
     * Does nothing when there is only one page.
     *
     * @param player      the target player
     * @param commandBase the command prefix (e.g. {@code "/sculpt blueprint list"})
     * @param page        current page (1-based)
     * @param totalPages  total number of pages
     */
    public static void sendPageBar(final Player player, final String commandBase,
                                   final int page, final int totalPages) {
        if (totalPages <= 1) return;

        String prevHover = getTranslated(player, "command.sculpt.page.previous_hover");
        String nextHover = getTranslated(player, "command.sculpt.page.next_hover");
        String pageInfo = getTranslated(player, "command.sculpt.page.info", page, totalPages);

        StringBuilder sb = new StringBuilder();
        if (page > 1) {
            sb.append("<click:run_command:").append(commandBase).append(" --page ").append(page - 1).append(">")
              .append("<hover:show_text:\"").append(prevHover).append("\">")
              .append("<#C2C7D3>◀</#C2C7D3></hover></click>");
        } else {
            sb.append("<#7A8B9C>◀</#7A8B9C>");
        }
        sb.append(pageInfo);
        if (page < totalPages) {
            sb.append("<click:run_command:").append(commandBase).append(" --page ").append(page + 1).append(">")
              .append("<hover:show_text:\"").append(nextHover).append("\">")
              .append("<#C2C7D3>▶</#C2C7D3></hover></click>");
        } else {
            sb.append("<#7A8B9C>▶</#7A8B9C>");
        }

        sendMessage(player, sb.toString());
    }

    /**
     * Get a translated string without sending it (useful for embedding in
     * other format strings or constructing compound messages).
     *
     * @param player the player whose language to use
     * @param key    the translation key
     * @param args   optional format arguments
     * @return the translated string, or the raw key if the manager is
     *         unavailable
     */
    public static String getTranslated(final Player player, final String key, final Object... args) {
        if (languageManager == null) return key;
        return languageManager.getMessage(player, key, args);
    }
}
