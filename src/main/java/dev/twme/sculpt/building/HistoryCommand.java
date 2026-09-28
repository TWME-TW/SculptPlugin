package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.MessageUtil;

/** {@code /sculpt undo [steps]} and {@code /sculpt redo [steps]}. */
public final class HistoryCommand {

    private final BuildToolkit toolkit;

    public HistoryCommand(final BuildToolkit toolkit) {
        this.toolkit = toolkit;
    }

    public boolean execute(final CommandSender sender, final boolean redo, final String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendTranslated(sender, "building.player_only");
            return true;
        }
        if (!player.hasPermission(SculptPermissions.UNDO)) {
            MessageUtil.sendTranslated(player, "general.no_permission");
            MessageUtil.sendTranslated(player, "general.required_perm", SculptPermissions.UNDO);
            return true;
        }
        final int maximum = toolkit.limits().historyMaxEntries();
        int steps = 1;
        if (args.length > 1) {
            MessageUtil.sendTranslated(player, redo ? "building.redo.usage" : "building.undo.usage");
            return true;
        }
        if (args.length == 1) {
            try {
                steps = Integer.parseInt(args[0]);
            } catch (final NumberFormatException invalid) {
                steps = -1;
            }
            if (steps < 1 || steps > maximum) {
                MessageUtil.sendTranslated(player, "building.undo.invalid_steps", maximum);
                return true;
            }
        }
        if (!toolkit.isReady()) {
            MessageUtil.sendTranslated(player, "building.not_ready");
            return true;
        }
        if (!toolkit.engine().tryBegin(player)) {
            MessageUtil.sendTranslated(player, "building.busy");
            return true;
        }
        toolkit.engine().revert(player, redo, steps);
        return true;
    }

    public List<String> complete(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(SculptPermissions.UNDO) || args.length != 1) return List.of();
        return StringUtil.copyPartialMatches(args[0], List.of("1", "5", "10"), new ArrayList<>());
    }
}
