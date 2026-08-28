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
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                MessageUtil.sendTranslated(sender, "sculptmode.usage");
                return true;
            }
            MessageUtil.sendTranslatedActionBar(p,
                !plugin.isSculptMode(p) ? "sculptmode.disabled"
                    : plugin.isSculptModeSuspended(p)
                        ? "sculptcontrols.paused" : "sculptmode.enabled");
            return true;
        }
        if (args.length > 2) {
            MessageUtil.sendTranslated(sender, "sculptmode.usage");
            return true;
        }

        final boolean enabled;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on" -> enabled = true;
            case "off" -> enabled = false;
            default -> {
                MessageUtil.sendTranslated(sender, "sculptmode.unknown", args[0]);
                return true;
            }
        }

        if (!checkPerm(sender, enabled
                ? SculptPermissions.MODE_ON : SculptPermissions.MODE_OFF)) return true;

        final Player target;
        if (args.length == 2) {
            target = sender.getServer().getPlayerExact(args[1]);
            if (target == null) {
                MessageUtil.sendTranslated(sender, "sculptmode.no_player", args[1]);
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            MessageUtil.sendTranslated(sender, "sculptmode.usage");
            return true;
        }

        plugin.setSculptMode(target, enabled);
        MessageUtil.sendTranslatedActionBar(target, enabled
            ? "sculptmode.enabled" : "sculptmode.disabled");
        if (!sender.equals(target)) {
            MessageUtil.sendTranslated(sender,
                enabled ? "sculptmode.other_enabled" : "sculptmode.other_disabled",
                target.getName());
        }
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
        if (args.length == 2) {
            final String mode = args[0].toLowerCase(Locale.ROOT);
            final String permission = switch (mode) {
                case "on" -> SculptPermissions.MODE_ON;
                case "off" -> SculptPermissions.MODE_OFF;
                default -> null;
            };
            if (permission != null && hasPerm(sender, permission)) {
                final List<String> playerNames = sender.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .toList();
                return StringUtil.copyPartialMatches(args[1], playerNames, new ArrayList<>());
            }
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
