package dev.twme.sculpt.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Executor and tab completer for {@code /sculpt mode}.
 *
 * Fill and rendering are configured independently by {@code /sculpt fill}
 * and {@code /sculpt display}.
 */
public final class SculptModeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("on", "off");

    private final Sculpt plugin;

    public SculptModeCommand(final Sculpt plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command cmd,
                             final String label, final String[] args) {
        if (!(sender instanceof Player p)) {
            MessageUtil.sendTranslated(sender, "sculptmode.player_only");
            return true;
        }
        if (args.length == 0) {
            MessageUtil.sendTranslatedActionBar(p,
                !plugin.isSculptMode(p) ? "sculptmode.disabled"
                    : plugin.isSculptModeSuspended(p)
                        ? "sculptcontrols.paused" : "sculptmode.enabled");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> setMode(p, true);
            case "off" -> setMode(p, false);
            default -> {
                MessageUtil.sendTranslated(sender, "sculptmode.unknown", args[0]);
                yield true;
            }
        };
    }

    private boolean setMode(final Player p, final boolean enabled) {
        if (!checkPerm(p, enabled
                ? SculptPermissions.MODE_ON : SculptPermissions.MODE_OFF)) return true;
        plugin.setSculptMode(p, enabled);
        MessageUtil.sendTranslatedActionBar(p, enabled
            ? "sculptmode.enabled" : "sculptmode.disabled");
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command cmd,
                                      final String label, final String[] args) {
        if (args.length == 1) {
            final List<String> allowed = SUBCOMMANDS.stream()
                .filter(sub -> switch (sub) {
                    case "on" -> hasPerm(sender, SculptPermissions.MODE_ON);
                    case "off" -> hasPerm(sender, SculptPermissions.MODE_OFF);
                    default -> false;
                })
                .toList();
            return StringUtil.copyPartialMatches(args[0], allowed, new ArrayList<>());
        }
        return List.of();
    }

    private static boolean checkPerm(final CommandSender sender, final String permission) {
        if (hasPerm(sender, permission)) return true;
        MessageUtil.sendTranslated(sender, "general.no_permission");
        MessageUtil.sendTranslated(sender, "general.required_perm", permission);
        return false;
    }

    private static boolean hasPerm(final CommandSender sender, final String permission) {
        return sender.hasPermission(permission);
    }
}
