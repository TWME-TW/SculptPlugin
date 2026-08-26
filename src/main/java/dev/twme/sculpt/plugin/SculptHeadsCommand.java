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
import dev.twme.sculpt.gui.HeadBrowserGUI;
import dev.twme.sculpt.util.MessageUtil;

/**
 * /sculpt heads — 開啟頭顱瀏覽器 GUI。
 *
 * <p>子命令：
 * <ul>
 *   <li>（無參數）— 瀏覽所有已載入的 baked heads</li>
 *   <li>{@code search <search> [grid]} — 搜尋方塊 ID，可指定 grid</li>
 * </ul>
 *
 * <p>在 Chest GUI 中瀏覽所有已載入的 baked heads，依 Grid 篩選，
 * 點擊頭顱物品即可取得該頭顱（附 PDC 標記）。
 */
public final class SculptHeadsCommand implements CommandExecutor, TabCompleter {

    private final Sculpt plugin;

    public SculptHeadsCommand(final Sculpt plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command cmd,
                              final String label, final String[] args) {
        if (!hasPermission(sender)) {
            MessageUtil.sendTranslated(sender, "general.no_permission");
            MessageUtil.sendTranslated(sender, "general.required_perm", SculptPermissions.HEADS);
            return true;
        }
        if (!(sender instanceof final Player player)) {
            MessageUtil.sendTranslated(sender, "general.player_only");
            return true;
        }

        if (args.length == 0) {
            // /sculpt heads — 直接開啟瀏覽器
            new HeadBrowserGUI(plugin, player).open();
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "search" -> handleSearch(player, args);
            default -> {
                MessageUtil.sendTranslated(player, "command.sculptheads.usage");
                yield true;
            }
        };
    }

    /**
     * /sculpt heads search {@code <search> [grid]}
     * <p>
     * 在指定的 grid（或全部）中搜尋名稱包含 {@code search} 的頭顱。
     * 搜尋不區分大小寫，比對 BlockKey 的完整字串（如 minecraft:stone）。
     */
    private boolean handleSearch(final Player player, final String[] args) {
        if (args.length < 2) {
            MessageUtil.sendTranslated(player, "command.sculptheads.search.usage");
            return true;
        }

        final String query = args[1];
        int grid = -1;

        if (args.length >= 3) {
            try {
                grid = Integer.parseInt(args[2]);
                // 只允許 2, 4, 8, 16
                if (grid != 2 && grid != 4 && grid != 8 && grid != 16) {
                    MessageUtil.sendTranslated(player, "command.sculptheads.search.invalid_grid", args[2]);
                    return true;
                }
            } catch (final NumberFormatException e) {
                MessageUtil.sendTranslated(player, "command.sculptheads.search.invalid_grid", args[2]);
                return true;
            }
        }

        new HeadBrowserGUI(plugin, player, query, grid).open();
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command cmd,
                                      final String label, final String[] args) {
        if (!hasPermission(sender)) return List.of();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], List.of("search"), new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("search")) {
            return StringUtil.copyPartialMatches(args[2], List.of("2", "4", "8", "16"),
                new ArrayList<>());
        }
        return List.of();
    }

    private static boolean hasPermission(final CommandSender sender) {
        return sender.hasPermission(SculptPermissions.HEADS);
    }
}
