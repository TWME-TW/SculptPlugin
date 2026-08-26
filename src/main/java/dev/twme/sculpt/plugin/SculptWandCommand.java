package dev.twme.sculpt.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.blueprint.BlueprintManager;
import dev.twme.sculpt.util.MessageUtil;
import dev.twme.sculpt.util.WandTool;

/**
 * Executor and tab completer for {@code /sculpt tool}.
 *
 * <p>Unifies the two selector tool commands into one root command:
 * <ul>
 *   <li>{@code selector} — give the region selection wand</li>
 *   <li>{@code blueprint} — give the blueprint selector tool</li>
 * </ul>
 *
 * <p>Each subcommand optionally accepts a player name to give the tool
 * to another online player.
 */
public final class SculptWandCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("selector", "blueprint");

    private final Sculpt plugin;
    private final BlueprintManager bpManager;

    public SculptWandCommand(final Sculpt plugin, final BlueprintManager bpManager) {
        this.plugin = plugin;
        this.bpManager = bpManager;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command cmd,
                             final String label, final String[] args) {
        if (args.length == 0) {
            MessageUtil.sendTranslated(sender, "sculptwand.usage");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "selector" -> handleSelector(sender, args);
            case "blueprint" -> handleBlueprint(sender, args);
            default -> {
                MessageUtil.sendTranslated(sender, "sculptwand.unknown", args[0]);
                yield true;
            }
        };
    }

    /**
     * /sculpt tool selector [player] — give the region selection wand.
     */
    private boolean handleSelector(final CommandSender sender, final String[] args) {
        if (!checkPerm(sender, SculptPermissions.TOOL_SELECTOR)) return true;

        if (args.length > 1) {
            final Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                MessageUtil.sendTranslated(sender, "sculptwand.no_player", args[1]);
                return true;
            }
            WandTool.giveToPlayer(target, plugin);
            MessageUtil.sendTranslated(sender, "sculptwand.selector.other", target.getName());
            if (!sender.equals(target)) {
                MessageUtil.sendTranslated(target, "sculptwand.selector.given");
            }
            return true;
        }

        if (!(sender instanceof Player p)) {
            MessageUtil.sendTranslated(sender, "sculptwand.player_only");
            return true;
        }
        WandTool.giveToPlayer(p, plugin);
        MessageUtil.sendTranslated(p, "sculptwand.selector.self");
        return true;
    }

    /**
     * /sculpt tool blueprint [player] — give the blueprint selector tool.
     */
    private boolean handleBlueprint(final CommandSender sender, final String[] args) {
        if (!checkPerm(sender, SculptPermissions.TOOL_BLUEPRINT)) return true;

        Player target;
        if (args.length > 1) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                MessageUtil.sendTranslated(sender, "sculptwand.no_player", args[1]);
                return true;
            }
        } else {
            if (!(sender instanceof Player p)) {
                MessageUtil.sendTranslated(sender, "sculptwand.player_only");
                return true;
            }
            target = p;
        }

        target.getInventory().addItem(bpManager.createSelectorItem());
        if (target == sender || sender.equals(target)) {
            MessageUtil.sendTranslated(target, "sculptwand.blueprint.self");
        } else {
            MessageUtil.sendTranslated(sender, "sculptwand.blueprint.other", target.getName());
            MessageUtil.sendTranslated(target, "sculptwand.blueprint.given");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command cmd,
                                      final String label, final String[] args) {
        if (args.length == 1) {
            final List<String> allowed = SUBCOMMANDS.stream()
                .filter(sub -> hasPerm(sender, SculptPermissions.tool(sub)))
                .toList();
            return StringUtil.copyPartialMatches(args[0], allowed, new ArrayList<>());
        }
        if (args.length == 2 && SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))
                && hasPerm(sender,
                    SculptPermissions.tool(args[0].toLowerCase(Locale.ROOT)))) {
            // Suggest online player names for any subcommand
            final List<String> playerNames = sender.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
            return StringUtil.copyPartialMatches(args[1], playerNames, new ArrayList<>());
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
