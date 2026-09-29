package dev.twme.sculpt.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

/**
 * {@code /sculpt}: opens the editor menu. Day-to-day editing happens in the
 * editor; commands remain for automation, undo, blueprint files, and
 * administration.
 */
public final class SculptCommand implements CommandExecutor, TabCompleter {

    private static final List<String> PRIMARY_SUBCOMMANDS = List.of(
            "help", "edit", "undo", "redo", "blueprint", "admin");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of(
            "list", "teleport", "reload", "status");
    /** Subcommands of earlier versions whose features moved into the editor. */
    static final Set<String> MOVED_TO_EDITOR = Set.of(
            "resolution", "preview", "mode", "fill", "display", "convert", "replace",
            "relight", "build", "brush", "tool", "heads");

    private final Sculpt plugin;
    private final SculptBlueprintCommand blueprintCommand;

    public SculptCommand(Sculpt plugin, SculptBlueprintCommand blueprintCommand) {
        this.plugin = plugin;
        this.blueprintCommand = blueprintCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player && plugin.isEditorAvailable()) {
                dev.twme.sculpt.editor.ui.EditorDialogs.main(player, plugin.getEditorService());
            } else {
                sendHelp(sender);
            }
            return true;
        }
        final String root = args[0].toLowerCase(Locale.ROOT);
        return switch (root) {
            case "help" -> {
                sendHelp(sender);
                yield true;
            }
            case "edit" -> handleEdit(sender, args);
            case "undo" -> handleHistory(sender, false, args);
            case "redo" -> handleHistory(sender, true, args);
            case "blueprint" -> blueprintCommand.onCommand(sender, cmd, label, tail(args));
            case "admin" -> handleAdmin(sender, args);
            default -> {
                if (MOVED_TO_EDITOR.contains(root)) {
                    MessageUtil.sendTranslated(sender, "command.sculpt.moved_to_editor", root);
                } else {
                    MessageUtil.sendTranslated(sender, "command.sculpt.unknown_subcommand", args[0]);
                }
                yield true;
            }
        };
    }

    /** {@code /sculpt edit [on|off]} */
    private boolean handleEdit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendTranslated(sender, "general.player_only");
            return true;
        }
        if (!checkPerm(sender, SculptPermissions.EDIT)) return true;
        if (!plugin.isEditorAvailable()) {
            MessageUtil.sendTranslated(sender, "editor.unavailable");
            return true;
        }
        final var editor = plugin.getEditorService();
        final String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
        switch (mode) {
            case "on" -> editor.enter(player);
            case "off" -> editor.exit(player, true);
            case "toggle" -> {
                if (editor.isEditing(player)) editor.exit(player, true);
                else editor.enter(player);
            }
            default -> MessageUtil.sendTranslated(sender, "command.sculpt.edit.usage");
        }
        return true;
    }

    /** {@code /sculpt undo [steps]} and {@code /sculpt redo [steps]} */
    private boolean handleHistory(CommandSender sender, boolean redo, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendTranslated(sender, "general.player_only");
            return true;
        }
        if (!checkPerm(sender, SculptPermissions.UNDO)) return true;
        final int maximum = plugin.getBuildEngine().limits().historyMaxEntries();
        int steps = 1;
        if (args.length > 2) {
            MessageUtil.sendTranslated(player, redo ? "building.redo.usage" : "building.undo.usage");
            return true;
        }
        if (args.length == 2) {
            try {
                steps = Integer.parseInt(args[1]);
            } catch (NumberFormatException invalid) {
                steps = -1;
            }
            if (steps < 1 || steps > maximum) {
                MessageUtil.sendTranslated(player, "building.undo.invalid_steps", maximum);
                return true;
            }
        }
        if (plugin.getHeadResolver() == null) {
            MessageUtil.sendTranslated(player, "building.not_ready");
            return true;
        }
        if (plugin.isEditorAvailable()) {
            plugin.getEditorService().revert(player, redo, steps);
            return true;
        }
        if (!plugin.getBuildEngine().tryBegin(player)) {
            MessageUtil.sendTranslated(player, "building.busy");
            return true;
        }
        final boolean isRedo = redo;
        plugin.getBuildEngine().revert(player, redo, steps, new dev.twme.sculpt.building.EditObserver() {
            @Override
            public void onFinish(dev.twme.sculpt.building.EditReport report,
                                 List<dev.twme.sculpt.building.BlockPos> changed) {
                dev.twme.sculpt.building.ReportMessages.sendRevert(player, isRedo, report);
            }
        });
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendTranslated(sender, "command.sculpt.help.admin_usage");
            return true;
        }
        final String[] coreArgs = tail(args);
        return switch (coreArgs[0].toLowerCase(Locale.ROOT)) {
            case "list" -> handleList(sender, coreArgs);
            case "teleport" -> handleTeleport(sender, coreArgs);
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            default -> {
                MessageUtil.sendTranslated(sender, "command.sculpt.help.admin_usage");
                yield true;
            }
        };
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.sendTranslated(sender, "command.sculpt.help.header");
        for (String sub : allowedPrimaryCommands(sender::hasPermission)) {
            if (sub.equals("help")) continue;
            MessageUtil.sendTranslated(sender, "command.sculpt.help." + sub);
        }
        MessageUtil.sendTranslated(sender, "command.sculpt.help.hint");
    }

    /**
     * /sculpt admin list [--page <n>] — list active SculptBlocks in the current world.
     * Each entry is clickable to teleport via {@code /sculpt admin teleport <loc>}.
     */
    private boolean handleList(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            MessageUtil.sendTranslated(sender, "command.sculpt.list.player_only");
            return true;
        }
        if (!checkPerm(sender, SculptPermissions.ADMIN_LIST)) return true;

        int page = 1;
        for (int i = 1; i < args.length; i++) {
            if ("--page".equals(args[i]) && i + 1 < args.length) {
                try {
                    page = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    MessageUtil.sendTranslated(sender, "command.sculpt.list.invalid_page");
                    return true;
                }
            }
        }

        List<SculptBlock> blocks = plugin.getActiveBlocks().stream()
                .filter(b -> b.pos.getWorld().equals(p.getWorld()))
                .toList();
        if (blocks.isEmpty()) {
            MessageUtil.sendTranslated(sender, "command.sculpt.list.empty");
            return true;
        }

        int totalPages = (blocks.size() + MessageUtil.PAGE_SIZE - 1) / MessageUtil.PAGE_SIZE;
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;
        int from = (page - 1) * MessageUtil.PAGE_SIZE;
        int to = Math.min(from + MessageUtil.PAGE_SIZE, blocks.size());
        final int currentPage = page;
        final List<SculptBlock> pageBlocks = blocks.subList(from, to);

        if (FoliaScheduler.isFolia()) {
            final List<CompletableFuture<BlockListEntry>> futures = pageBlocks.stream()
                .map(this::readListEntryOnRegion)
                .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING,
                            "Failed to read SculptBlock list on a region thread", error);
                        FoliaScheduler.runEntityTask(plugin, p, () ->
                            MessageUtil.sendTranslated(p, "command.sculpt.list.read_failed"));
                    } else {
                        final List<BlockListEntry> entries = futures.stream()
                            .map(CompletableFuture::join)
                            .toList();
                        FoliaScheduler.runEntityTask(plugin, p,
                            () -> sendListPage(p, blocks.size(), currentPage, totalPages, entries));
                    }
                });
            return true;
        }

        final List<BlockListEntry> entries = pageBlocks.stream()
            .map(this::readListEntry)
            .toList();
        sendListPage(p, blocks.size(), currentPage, totalPages, entries);
        return true;
    }

    private CompletableFuture<BlockListEntry> readListEntryOnRegion(final SculptBlock block) {
        final CompletableFuture<BlockListEntry> result = new CompletableFuture<>();
        FoliaScheduler.runRegionTask(plugin, block.pos, () -> {
            try {
                result.complete(readListEntry(block));
            } catch (final RuntimeException error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private BlockListEntry readListEntry(final SculptBlock block) {
        return new BlockListEntry(formatLoc(block.pos),
            block.originalBlockData.getAsString(), block.root.collectLeaves().size());
    }

    private void sendListPage(final Player player, final int total, final int page,
                              final int totalPages, final List<BlockListEntry> entries) {
        if (!player.isOnline()) return;
        MessageUtil.sendTranslated(player, "command.sculpt.list.header", total);
        for (final BlockListEntry entry : entries) {
            MessageUtil.sendTranslated(player, "command.sculpt.list.entry",
                entry.location(), entry.blockData(), entry.leafCount());
        }
        MessageUtil.sendPageBar(player, "/sculpt admin list", page, totalPages);
    }

    private record BlockListEntry(String location, String blockData, int leafCount) {}

    /**
     * /sculpt admin teleport <world,x,y,z> — teleport to a SculptBlock location.
     *
     * <p>The location format matches the output of {@link #formatLoc}:
     * {@code worldName,blockX,blockY,blockZ}.  The target block does not
     * need to be an active SculptBlock (the command simply teleports).
     */
    private boolean handleTeleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            MessageUtil.sendTranslated(sender, "command.sculpt.tp.player_only");
            return true;
        }
        if (!checkPerm(sender, SculptPermissions.ADMIN_TELEPORT)) return true;

        if (args.length < 2) {
            MessageUtil.sendTranslated(sender, "command.sculpt.tp.usage");
            return true;
        }

        // Parse "world,x,y,z" format
        String locStr = args[1];
        String[] parts = locStr.split(",", 4);
        if (parts.length != 4) {
            MessageUtil.sendTranslated(sender, "command.sculpt.tp.invalid_format", locStr);
            return true;
        }

        org.bukkit.World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            MessageUtil.sendTranslated(sender, "command.sculpt.tp.world_not_found", parts[0]);
            return true;
        }

        int x, y, z;
        try {
            x = Integer.parseInt(parts[1]);
            y = Integer.parseInt(parts[2]);
            z = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            MessageUtil.sendTranslated(sender, "command.sculpt.tp.invalid_format", locStr);
            return true;
        }

        // Clamp Y to world boundaries so the player doesn't end up in void/roof
        int maxY = world.getMaxHeight() - 1;
        y = Math.max(world.getMinHeight(), Math.min(y, maxY));

        Location target = new Location(world, x + 0.5, y, z + 0.5);
        if (FoliaScheduler.isFolia()) {
            p.teleportAsync(target).thenAccept(success ->
                FoliaScheduler.runEntityTask(plugin, p, () -> MessageUtil.sendTranslated(p,
                    success ? "command.sculpt.tp.teleported" : "command.sculpt.tp.failed",
                    formatLoc(target))));
        } else {
            p.teleport(target);
            MessageUtil.sendTranslated(sender, "command.sculpt.tp.teleported", formatLoc(target));
        }
        return true;
    }

    /**
     * /sculpt admin reload — reload config.yml.
     */
    private boolean handleReload(CommandSender sender) {
        if (!checkPerm(sender, SculptPermissions.ADMIN_RELOAD)) return true;
        try {
            plugin.reloadSculptConfig();
            MessageUtil.sendTranslated(sender, "general.reload_success",
                    plugin.sculptConfig().chunkGridSize());
        } catch (IllegalArgumentException e) {
            MessageUtil.sendTranslated(sender, "general.reload_failed", e.getMessage());
        }
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!checkPerm(sender, SculptPermissions.ADMIN_STATUS)) return true;
        final RuntimeHealth health = plugin.runtimeHealth();
        switch (health.status()) {
            case LOADING -> MessageUtil.sendTranslated(sender,
                "command.sculpt.status.loading", health.configuredGrid());
            case READY -> MessageUtil.sendTranslated(sender,
                "command.sculpt.status.ready", health.configuredGrid(),
                health.configuredGridBlocks(), health.totalIndexedBlocks(),
                health.runtimeBakeEnabled());
            case DEGRADED -> MessageUtil.sendTranslated(sender,
                "command.sculpt.status.degraded", health.configuredGrid());
            case FAILED -> MessageUtil.sendTranslated(sender,
                "command.sculpt.status.failed", health.failure());
        }
        return true;
    }

    // ---------------------------------------------------------------
    //  Tab completion
    // ---------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0],
                    allowedPrimaryCommands(sender::hasPermission), new ArrayList<>());
        }
        final String root = args[0].toLowerCase(Locale.ROOT);
        if (!canUsePrimary(sender::hasPermission, root)) return List.of();
        return switch (root) {
            case "edit" -> args.length == 2
                ? StringUtil.copyPartialMatches(args[1], List.of("on", "off"), new ArrayList<>())
                : List.of();
            case "undo", "redo" -> args.length == 2
                ? StringUtil.copyPartialMatches(args[1], List.of("1", "5", "10"), new ArrayList<>())
                : List.of();
            case "blueprint" -> blueprintCommand.onTabComplete(sender, cmd, label, tail(args));
            case "admin" -> completeAdmin(sender, args);
            default -> List.of();
        };
    }

    private List<String> completeAdmin(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return StringUtil.copyPartialMatches(args[1], allowedAdminSubcommands(sender), new ArrayList<>());
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("list")) {
            return StringUtil.copyPartialMatches(args[2], List.of("--page"), new ArrayList<>());
        }
        return List.of();
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static String formatLoc(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX()
                + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private static boolean isValidGrid(int grid) {
        for (int v : VALID_GRIDS_VALUES) {
            if (grid == v) return true;
        }
        return false;
    }

    private static final int[] VALID_GRIDS_VALUES = {1, 2, 4, 8, 16};

    // ---------------------------------------------------------------
    //  Permission gating
    // ---------------------------------------------------------------

    /**
     * @return true if {@code player} has permission to use grid size
     *         {@code gridN}. Required permission is
     *         {@code sculpt.command.resolution.N}.
     */
    public static boolean canUseGrid(Player player, int gridN) {
        return canUseGrid(player == null ? null : player::hasPermission, gridN);
    }

    /**
     * Predicate-based overload (testable without a live Player). Returns
     * true if the given {@code permissionChecker} grants the
     * {@code sculpt.command.resolution.N} permission for the requested size.
     */
    public static boolean canUseGrid(Predicate<String> permissionChecker, int gridN) {
        if (permissionChecker == null) return false;
        if (!isValidGrid(gridN)) return false;
        return permissionChecker.test(SculptPermissions.resolution(gridN));
    }

    /**
     * Find the largest grid size the player is allowed to use. Returns
     * -1 if none of the valid grid sizes are permitted.
     */
    public static int largestAllowedGrid(Player player) {
        return largestAllowedGrid(player == null ? null : player::hasPermission);
    }

    /** Predicate-based overload (testable). */
    public static int largestAllowedGrid(Predicate<String> permissionChecker) {
        if (permissionChecker == null) return -1;
        // Walk from largest (16) to smallest (1) and return the first hit.
        for (int i = VALID_GRIDS_VALUES.length - 1; i >= 0; i--) {
            int g = VALID_GRIDS_VALUES[i];
            if (permissionChecker.test(SculptPermissions.resolution(g))) return g;
        }
        return -1;
    }

    /**
     * @return the list of grid sizes (as strings) the player is allowed to
     *         use, used for tab completion.
     */
    public static List<String> allowedGridSizes(Player player) {
        return allowedGridSizes(player == null ? null : player::hasPermission);
    }

    /** Predicate-based overload (testable). */
    public static List<String> allowedGridSizes(Predicate<String> permissionChecker) {
        List<String> out = new ArrayList<>();
        if (permissionChecker == null) return out;
        for (int g : VALID_GRIDS_VALUES) {
            if (permissionChecker.test(SculptPermissions.resolution(g))) {
                out.add(String.valueOf(g));
            }
        }
        return out;
    }

    /**
     * The primary subcommands the sender may use, for help and completion.
     */
    static List<String> allowedPrimaryCommands(Predicate<String> permissionChecker) {
        return PRIMARY_SUBCOMMANDS.stream()
                .filter(sub -> canUsePrimary(permissionChecker, sub))
                .toList();
    }

    private static boolean canUsePrimary(Predicate<String> permissionChecker, String sub) {
        return switch (sub) {
            case "help" -> true;
            case "edit" -> permissionChecker.test(SculptPermissions.EDIT);
            case "undo", "redo" -> permissionChecker.test(SculptPermissions.UNDO);
            case "blueprint" -> SculptPermissions.BLUEPRINT_PERMISSIONS.stream()
                    .anyMatch(permissionChecker);
            case "admin" -> hasAny(permissionChecker,
                    SculptPermissions.ADMIN_LIST, SculptPermissions.ADMIN_TELEPORT,
                    SculptPermissions.ADMIN_RELOAD, SculptPermissions.ADMIN_STATUS);
            default -> false;
        };
    }

    private static boolean hasAny(Predicate<String> permissionChecker, String... permissions) {
        for (String permission : permissions) {
            if (permissionChecker.test(permission)) return true;
        }
        return false;
    }

    private static List<String> allowedAdminSubcommands(CommandSender sender) {
        return ADMIN_SUBCOMMANDS.stream()
                .filter(sub -> sender.hasPermission(SculptPermissions.admin(sub)))
                .toList();
    }

    private static String[] tail(String[] args) {
        final String[] result = new String[Math.max(0, args.length - 1)];
        if (result.length > 0) System.arraycopy(args, 1, result, 0, result.length);
        return result;
    }

    private static boolean checkPerm(CommandSender sender, String perm) {
        if (sender.hasPermission(perm)) return true;
        MessageUtil.sendTranslated(sender, "general.no_permission");
        MessageUtil.sendTranslated(sender, "general.required_perm", perm);
        return false;
    }

}
