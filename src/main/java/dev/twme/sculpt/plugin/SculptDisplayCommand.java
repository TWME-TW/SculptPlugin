package dev.twme.sculpt.plugin;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.util.MessageUtil;

/** Configures the visual backend independently from SculptMode and fill. */
public final class SculptDisplayCommand implements CommandExecutor, TabCompleter {

    private static final List<String> CHOICES = List.of("head", "textdisplay", "auto");

    private final Sculpt plugin;

    public SculptDisplayCommand(final Sculpt plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendTranslated(sender, "sculptdisplay.player_only");
            return true;
        }
        if (args.length == 0) {
            sendMode(player, plugin.displayModeFor(player));
            return true;
        }
        final SculptDisplayMode mode = SculptDisplayMode.parse(args[0], null);
        if (mode == null) {
            MessageUtil.sendTranslated(sender, "sculptdisplay.unknown", args[0]);
            return true;
        }
        final String permission = SculptPermissions.display(mode.id());
        if (!checkPerm(sender, permission)) return true;
        plugin.setDisplayMode(player, mode);
        sendMode(player, mode);
        return true;
    }

    private static void sendMode(
            final Player player,
            final SculptDisplayMode mode) {
        MessageUtil.sendTranslatedActionBar(player, "sculptdisplay." + mode.id());
    }

    @Override
    public List<String> onTabComplete(
            final CommandSender sender,
            final Command command,
            final String label,
            final String[] args) {
        if (args.length != 1) return List.of();
        final List<String> allowed = CHOICES.stream()
            .filter(choice -> sender.hasPermission(SculptPermissions.display(choice)))
            .toList();
        return StringUtil.copyPartialMatches(args[0], allowed, new ArrayList<>());
    }

    private static boolean checkPerm(
            final CommandSender sender,
            final String permission) {
        if (sender.hasPermission(permission)) return true;
        MessageUtil.sendTranslated(sender, "general.no_permission");
        MessageUtil.sendTranslated(sender, "general.required_perm", permission);
        return false;
    }
}
