package dev.twme.sculpt.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.skin.HeadsRegistry;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;
/**
 * Subcommand dispatcher for /sculpt. Implements both {@link CommandExecutor}
 * and {@link TabCompleter} so the Bukkit registration can use a single
 * instance for both roles.
 */
public final class SculptCommand implements CommandExecutor, TabCompleter {

    private static final List<String> PRIMARY_SUBCOMMANDS = List.of(
            "help", "resolution", "preview", "mode", "fill", "display",
            "convert", "replace", "relight", "tool",
            "blueprint", "heads", "admin");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of(
            "list", "teleport", "reload", "status");

    private final Sculpt plugin;
    private final SculptModeCommand modeCommand;
    private final SculptFillCommand fillCommand;
    private final SculptDisplayCommand displayCommand;
    private final SculptWandCommand toolCommand;
    private final SculptBlueprintCommand blueprintCommand;
    private final SculptHeadsCommand headsCommand;
    private final SculptReplaceCommand replaceCommand;
    private final SculptRelightCommand relightCommand;

    public SculptCommand(Sculpt plugin, SculptModeCommand modeCommand,
                         SculptFillCommand fillCommand,
                         SculptDisplayCommand displayCommand,
                         SculptWandCommand toolCommand,
                         SculptBlueprintCommand blueprintCommand,
                         SculptHeadsCommand headsCommand) {
        this.plugin = plugin;
        this.modeCommand = modeCommand;
        this.fillCommand = fillCommand;
        this.displayCommand = displayCommand;
        this.toolCommand = toolCommand;
        this.blueprintCommand = blueprintCommand;
        this.headsCommand = headsCommand;
        this.replaceCommand = new SculptReplaceCommand(plugin);
        this.relightCommand = new SculptRelightCommand(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                sendHelp(sender);
                yield true;
            }
            case "resolution" -> handleResolution(sender, args);
            case "preview" -> handlePreview(sender, args);
            case "mode" -> modeCommand.onCommand(sender, cmd, label, tail(args));
            case "fill" -> fillCommand.onCommand(sender, cmd, label, tail(args));
            case "display" -> displayCommand.onCommand(sender, cmd, label, tail(args));
            case "convert" -> handleConvert(sender, args);
            case "replace" -> replaceCommand.execute(sender, tail(args));
            case "relight" -> relightCommand.execute(sender, tail(args));
            case "tool" -> toolCommand.onCommand(sender, cmd, label, tail(args));
            case "blueprint" -> blueprintCommand.onCommand(sender, cmd, label, tail(args));
            case "heads" -> headsCommand.onCommand(sender, cmd, label, tail(args));
            case "admin" -> handleAdmin(sender, args);
            default -> {
                MessageUtil.sendTranslated(sender, "command.sculpt.unknown_subcommand", args[0]);
                yield true;
            }
        };
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
        if (canUsePrimary(sender::hasPermission, "resolution")
                || canUsePrimary(sender::hasPermission, "preview")
                || canUsePrimary(sender::hasPermission, "mode")
                || canUsePrimary(sender::hasPermission, "fill")
                || canUsePrimary(sender::hasPermission, "display")
                || canUsePrimary(sender::hasPermission, "convert")
                || canUsePrimary(sender::hasPermission, "replace")
                || canUsePrimary(sender::hasPermission, "relight")) {
            MessageUtil.sendTranslated(sender, "command.sculpt.help.sculpting_header");
            if (canUsePrimary(sender::hasPermission, "resolution"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.resolution");
            if (canUsePrimary(sender::hasPermission, "preview"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.preview");
            if (canUsePrimary(sender::hasPermission, "mode"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.mode");
            if (canUsePrimary(sender::hasPermission, "fill"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.fill");
            if (canUsePrimary(sender::hasPermission, "display"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.display");
            if (canUsePrimary(sender::hasPermission, "convert"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.convert");
            if (canUsePrimary(sender::hasPermission, "replace"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.replace");
            if (canUsePrimary(sender::hasPermission, "relight"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.relight");
        }
        if (canUsePrimary(sender::hasPermission, "tool")
                || canUsePrimary(sender::hasPermission, "blueprint")
                || canUsePrimary(sender::hasPermission, "heads")) {
            MessageUtil.sendTranslated(sender, "command.sculpt.help.content_header");
            if (canUsePrimary(sender::hasPermission, "tool"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.tool");
            if (canUsePrimary(sender::hasPermission, "blueprint"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.blueprint");
            if (canUsePrimary(sender::hasPermission, "heads"))
                MessageUtil.sendTranslated(sender, "command.sculpt.help.heads");
        }
        if (canUsePrimary(sender::hasPermission, "admin")) {
            MessageUtil.sendTranslated(sender, "command.sculpt.help.admin");
        }
        MessageUtil.sendTranslated(sender, "command.sculpt.help.hint");
    }

    // ---------------------------------------------------------------
    //  Subcommand handlers
    // ---------------------------------------------------------------

    /**
     * /sculpt resolution [gridN] — show or set the player's preferred grid size.
     *
     * <p>With {@code gridN} (one of 1, 2, 4, 8, 16), store the grid
     * resolution for future sculpt operations. Does NOT modify any block.
     * The stored size is used by auto-sculpt on left-click / block-break
     * and by the hover preview.
     *
     * <p>Without arguments, reports the player's current grid resolution.
     */
    private boolean handleResolution(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            MessageUtil.sendTranslated(sender, "command.sculpt.grid.player_only");
            return true;
        }
        if (!checkPerm(sender, SculptPermissions.RESOLUTION)) return true;

        // With gridN: just store the player's preference, don't touch blocks.
        if (args.length > 1) {
            try {
                int parsed = Integer.parseInt(args[1]);
                if (!isValidGrid(parsed)) {
                    MessageUtil.sendTranslated(sender, "command.sculpt.grid.invalid_grid");
                    return true;
                }
                if (!canUseGrid(p, parsed)) {
                    MessageUtil.sendTranslated(sender, "command.sculpt.grid.no_permission_grid", parsed);
                    MessageUtil.sendTranslated(sender, "command.sculpt.grid.required_perm", parsed);
                    return true;
                }
                plugin.setGridSizeFor(p, parsed);
                // Show a brief full-grid overlay on the looked-at block
                // so the player can see what this grid density looks like.
                Block target = p.getTargetBlockExact(5);
                if (target != null && !target.getType().isAir()) {
                    Location loc = target.getLocation();
                    BlockData bd = target.getBlockData().clone();
                    BlockKey bk = BlockKey.of(bd.getMaterial().getKey().toString());
                    HeadsRegistry reg = plugin.getHeadsRegistry(parsed);
                    // The command runs on the player/region thread. Only use
                    // an already-resident index here; a cold catalog read is
                    // scheduled by the resolver and must not stall the tick.
                    if (reg != null && reg.hasKnownBlock(bk) && !reg.hasLoaded(bk)) {
                        reg.prefetchIndex(bk);
                    }
                    if (reg != null && reg.hasLoaded(bk)) {
                        BlockVariantResolver.Result r = BlockVariantResolver.resolve(
                                bd, bk, reg);
                        plugin.showFullGridPreview(p, loc, parsed, r.blockRotation());
                    } else {
                        // Block not baked — show rotation-identity overlay
                        // anyway so the player sees the grid pattern.
                        plugin.showFullGridPreview(p, loc, parsed,
                                new org.joml.Quaternionf());
                    }
                } else {
                    MessageUtil.sendTranslated(p, "command.sculpt.grid.look_at_block");
                }
                MessageUtil.sendTranslated(p, "command.sculpt.grid.set", parsed);
                return true;
            } catch (NumberFormatException e) {
                MessageUtil.sendTranslated(sender, "command.sculpt.grid.invalid_grid_arg", args[1]);
                return true;
            }
        }

        // No args: show current grid size (v2: holding tool = edit mode, no toggle)
        int currentGrid = plugin.gridSizeFor(p);
        MessageUtil.sendTranslated(p, "command.sculpt.grid.current", currentGrid);
        return true;
    }

    /**
     * /sculpt preview [on|off] — enable or disable the hover grid preview.
     *
     * <p>Without arguments, toggles the current state.  The default state is
     * controlled by the {@code sculpt.use.preview.auto} permission.
     */
    private boolean handlePreview(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            MessageUtil.sendTranslated(sender, "command.sculpt.preview.player_only");
            return true;
        }
        if (!checkPerm(sender, SculptPermissions.PREVIEW)) return true;

        if (args.length > 1) {
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "on" -> {
                    plugin.setHoverEnabled(p, true);
                    MessageUtil.sendTranslated(p, "command.sculpt.preview.enabled");
                    yield true;
                }
                case "off" -> {
                    plugin.setHoverEnabled(p, false);
                    MessageUtil.sendTranslated(p, "command.sculpt.preview.disabled");
                    yield true;
                }
                default -> {
                    MessageUtil.sendTranslated(p, "command.sculpt.preview.usage");
                    yield true;
                }
            };
        }

        // Toggle
        final boolean next = plugin.toggleHover(p);
        if (next) {
            MessageUtil.sendTranslated(p, "command.sculpt.preview.enabled");
        } else {
            MessageUtil.sendTranslated(p, "command.sculpt.preview.disabled");
        }
        return true;
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
     * /sculpt convert <barrier|shulker|null> [region|single] — apply a fill
     * strategy to existing SculptBlocks.
     *
     */
    private boolean handleConvert(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            MessageUtil.sendTranslated(sender, "command.sculpt.convert.player_only");
            return true;
        }
        if (!checkPerm(sender, SculptPermissions.CONVERT)) return true;

        if (args.length < 2) {
            MessageUtil.sendTranslated(sender, "command.sculpt.convert.usage");
            return true;
        }

        final FillMode targetFill = FillMode.parse(args[1], null);
        if (targetFill == null) {
            MessageUtil.sendTranslated(sender, "command.sculpt.convert.usage");
            return true;
        }

        // Determine scope: region or single (default: region if selection, else single)
        final boolean region;
        if (args.length > 2) {
            region = args[2].equalsIgnoreCase("region");
        } else {
            // Default: region if selection exists, single otherwise
            final dev.twme.sculpt.editor.RegionSelection sel =
                plugin.getWandListener() != null
                    ? plugin.getWandListener().getSelection(p)
                    : null;
            region = sel != null && sel.isValid();
        }

        final FillConverter converter = plugin.getFillConverter();
        if (converter == null) {
            MessageUtil.sendTranslated(sender, "command.sculpt.convert.not_initialized");
            return true;
        }
        final ItemStack probeItem = p.getInventory().getItemInMainHand().clone();

        if (region) {
            final dev.twme.sculpt.editor.RegionSelection sel =
                plugin.getWandListener() != null
                    ? plugin.getWandListener().getSelection(p)
                    : null;
            if (sel == null || !sel.isValid()) {
                MessageUtil.sendTranslated(sender, "command.sculpt.convert.no_selection");
                return true;
            }

            final List<SculptBlock> targets = plugin.getActiveBlocks().stream()
                .filter(block -> block.pos.getWorld() == sel.world()
                    && block.pos.getBlockX() >= sel.minX() && block.pos.getBlockX() <= sel.maxX()
                    && block.pos.getBlockY() >= sel.minY() && block.pos.getBlockY() <= sel.maxY()
                    && block.pos.getBlockZ() >= sel.minZ() && block.pos.getBlockZ() <= sel.maxZ())
                .toList();
            if (targets.isEmpty()) {
                MessageUtil.sendTranslated(sender, "command.sculpt.convert.no_target");
                return true;
            }

            MessageUtil.sendTranslated(sender,
                "command.sculpt.convert.changing", targets.size(), targetFill.id());
            scheduleConversions(p, targets, converter, targetFill, probeItem);
        } else {
            // Single mode — look at block
            final Block target = p.getTargetBlockExact(5);
            if (target == null || target.getType().isAir()) {
                MessageUtil.sendTranslated(sender, "command.sculpt.convert.no_target");
                return true;
            }
            final SculptBlock sculpt = plugin.getActiveBlock(BlockPosKey.of(target));
            if (sculpt == null) {
                MessageUtil.sendTranslated(sender, "command.sculpt.convert.no_target");
                return true;
            }
            scheduleConversions(p, List.of(sculpt), converter, targetFill, probeItem);
        }
        return true;
    }

    private void scheduleConversions(final Player player, final List<SculptBlock> targets,
                                     final FillConverter converter,
                                     final FillMode targetFill,
                                     final ItemStack probeItem) {
        final AtomicInteger remaining = new AtomicInteger(targets.size());
        final AtomicInteger changed = new AtomicInteger();
        final AtomicInteger protectedBlocks = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        for (final SculptBlock sculpt : targets) {
            final ItemStack targetProbeItem = probeItem.clone();
            FoliaScheduler.runRegionTask(plugin, sculpt.pos, () -> {
                try {
                    if (!plugin.canPlayerBuild(player, sculpt.pos.getBlock(), targetProbeItem)) {
                        protectedBlocks.incrementAndGet();
                    } else if (converter.setFill(sculpt, targetFill)) {
                        changed.incrementAndGet();
                    }
                } catch (final RuntimeException error) {
                    failed.incrementAndGet();
                    plugin.getLogger().log(Level.WARNING,
                        "Failed to convert SculptBlock at " + formatLoc(sculpt.pos), error);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        sendConversionResult(player, changed.get(), protectedBlocks.get(),
                            failed.get(), targetFill);
                    }
                }
            });
        }
    }

    private void sendConversionResult(final Player player, final int changed,
                                      final int protectedBlocks, final int failed,
                                      final FillMode targetFill) {
        FoliaScheduler.runEntityTask(plugin, player, () -> {
            if (changed > 0) {
                MessageUtil.sendTranslated(player,
                    "command.sculpt.convert.changed", changed, targetFill.id());
            } else if (protectedBlocks == 0 && failed == 0) {
                MessageUtil.sendTranslated(player,
                    "command.sculpt.convert.already", targetFill.id());
            }
            if (protectedBlocks > 0) {
                MessageUtil.sendTranslated(player, "command.sculpt.convert.protected",
                    protectedBlocks);
            }
            if (failed > 0) {
                MessageUtil.sendTranslated(player, "command.sculpt.convert.failed", failed);
            }
        });
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
        if (root.equals("help")) {
            return List.of();
        }
        if (root.equals("resolution")) return completeSubcommand(sender, args);
        if (root.equals("preview")) return completeSubcommand(sender, args);
        if (root.equals("convert")) return completeSubcommand(sender, args);
        if (root.equals("replace")) return replaceCommand.complete(sender, tail(args));
        if (root.equals("relight")) return List.of();
        if (root.equals("mode")) return modeCommand.onTabComplete(sender, cmd, label, tail(args));
        if (root.equals("fill")) return fillCommand.onTabComplete(sender, cmd, label, tail(args));
        if (root.equals("display")) return displayCommand.onTabComplete(sender, cmd, label, tail(args));
        if (root.equals("tool")) return toolCommand.onTabComplete(sender, cmd, label, tail(args));
        if (root.equals("blueprint")) return blueprintCommand.onTabComplete(sender, cmd, label, tail(args));
        if (root.equals("heads")) return headsCommand.onTabComplete(sender, cmd, label, tail(args));
        if (root.equals("admin")) return completeAdmin(sender, args);
        return List.of();
    }

    private List<String> completeAdmin(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return StringUtil.copyPartialMatches(args[1], allowedAdminSubcommands(sender), new ArrayList<>());
        }
        final String[] coreArgs = tail(args);
        if (!coreArgs[0].equalsIgnoreCase("list")) return List.of();
        return completeSubcommand(sender, coreArgs);
    }

    private List<String> completeSubcommand(CommandSender sender, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("resolution")) {
            // Suggest grid sizes the player is allowed to use.
            Player p = (sender instanceof Player) ? (Player) sender : null;
            return StringUtil.copyPartialMatches(
                    args[1], allowedGridSizes(p), new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("preview")) {
            return StringUtil.copyPartialMatches(args[1],
                    List.of("on", "off"), new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("convert")) {
            return StringUtil.copyPartialMatches(args[1],
                    List.of("barrier", "shulker", "null"), new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("convert")) {
            return StringUtil.copyPartialMatches(args[2],
                    List.of("region", "single"), new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            return StringUtil.copyPartialMatches(args[1],
                    List.of("--page"), new ArrayList<>());
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
     * Filter the global {@link #SUBCOMMANDS} list to only those the sender
     * has permission to use.  Used by tab completion so players only see
     * commands they are actually allowed to run.
     */
    static List<String> allowedPrimaryCommands(Predicate<String> permissionChecker) {
        return PRIMARY_SUBCOMMANDS.stream()
                .filter(sub -> canUsePrimary(permissionChecker, sub))
                .toList();
    }

    private static boolean canUsePrimary(Predicate<String> permissionChecker, String sub) {
        if (sub.equals("help")) return true;
        return switch (sub) {
            case "resolution" -> permissionChecker.test(SculptPermissions.RESOLUTION);
            case "preview" -> permissionChecker.test(SculptPermissions.PREVIEW);
            case "convert" -> permissionChecker.test(SculptPermissions.CONVERT);
            case "replace" -> permissionChecker.test(SculptPermissions.REPLACE);
            case "relight" -> permissionChecker.test(SculptPermissions.RELIGHT);
            case "mode" -> hasAny(permissionChecker,
                    SculptPermissions.MODE_ON, SculptPermissions.MODE_OFF);
            case "fill" -> hasAny(permissionChecker,
                    SculptPermissions.FILL_BARRIER, SculptPermissions.FILL_SHULKER,
                    SculptPermissions.FILL_NULL);
            case "display" -> hasAny(permissionChecker,
                    SculptPermissions.DISPLAY_HEAD,
                    SculptPermissions.DISPLAY_TEXTDISPLAY,
                    SculptPermissions.DISPLAY_AUTO);
            case "tool" -> hasAny(permissionChecker,
                    SculptPermissions.TOOL_SELECTOR, SculptPermissions.TOOL_BLUEPRINT);
            case "blueprint" -> SculptPermissions.BLUEPRINT_PERMISSIONS.stream()
                    .anyMatch(permissionChecker);
            case "heads" -> permissionChecker.test(SculptPermissions.HEADS);
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
