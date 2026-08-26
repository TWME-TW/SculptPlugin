package dev.twme.sculpt.blueprint;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.lang.LanguageManager;
import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** /sculpt blueprint … subcommand dispatcher. All user-visible text via MessageUtil. */
public class BlueprintCommand implements CommandExecutor, TabCompleter {

    private static final String K = "command.sculpt.blueprint.";

    private static final List<String> BLUEPRINT_SUBCOMMANDS = List.of(
        "save", "list", "delete", "rename",
        "export", "import", "download",
        "bind", "unbind", "give", "settings",
        "publish", "unpublish"
    );
    private static final List<String> ROTATE_MODES = List.of("none","face","player","auto");
    private static final List<String> RY_VALUES = List.of("0","90","180","270");
    private static final List<String> FLIP_AXES = List.of("x","y","z");
    private static final List<String> VISIBILITY_VALUES = List.of("public","unlist","secret");

    private final Sculpt plugin;
    private final BlueprintManager bpManager;

    public BlueprintCommand(Sculpt plugin, BlueprintManager bpManager) {
        this.plugin = plugin;
        this.bpManager = bpManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendTranslated(sender, K + "player_only");
            return true;
        }
        if (!bpManager.isEnabled()) {
            MessageUtil.sendTranslated(player, K + "disabled");
            return true;
        }
        if (args.length < 2) {
            if (allowedSubcommands(player).isEmpty()) {
                MessageUtil.sendTranslated(player, "general.no_permission");
                MessageUtil.sendTranslated(player, "general.required_perm",
                    SculptPermissions.BLUEPRINT_ALL);
            } else {
                sendUsage(player);
            }
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        if (!BLUEPRINT_SUBCOMMANDS.contains(sub)) {
            sendUsage(player);
            return true;
        }
        final String permission = SculptPermissions.blueprint(sub);
        if (!hasPermission(player, permission)) {
            MessageUtil.sendTranslated(player, "general.no_permission");
            MessageUtil.sendTranslated(player, "general.required_perm", permission);
            return true;
        }
        String[] subArgs = Arrays.copyOfRange(args, 2, args.length);

        return switch (sub) {
            case "save" -> handleSave(player, subArgs);
            case "list" -> handleList(player, subArgs);
            case "delete" -> handleDelete(player, subArgs);
            case "rename" -> handleRename(player, subArgs);
            case "download" -> handleDownload(player, subArgs);
            case "bind" -> handleBind(player, subArgs);
            case "unbind" -> handleUnbind(player, subArgs);
            case "give" -> handleGive(player, subArgs);
            case "settings" -> handleSettings(player, subArgs);
            case "publish" -> handlePublish(player, subArgs);
            case "unpublish" -> handleUnpublish(player, subArgs);
            case "export" -> handleExport(player, subArgs);
            case "import" -> handleImport(player, subArgs);
            default -> throw new IllegalStateException("Unhandled blueprint operation: " + sub);
        };
    }

    // ====================== save ======================
    private boolean handleSave(Player p, String[] a) {
        if (a.length < 1) { MessageUtil.sendTranslated(p, K + "save.usage"); return true; }
        String rawName = a[0];
        boolean isPublic = false;
        // Path-based naming: "builds/myhouse" → folder="builds", name="myhouse"
        String folder = null;
        String name = rawName;
        int lastSlash = rawName.lastIndexOf('/');
        if (lastSlash >= 0) {
            folder = rawName.substring(0, lastSlash);
            name = rawName.substring(lastSlash + 1);
        }
        if (name.isEmpty()) { MessageUtil.sendTranslated(p, K + "save.usage"); return true; }
        for (int i = 1; i < a.length; i++) {
            if ("--public".equals(a[i])) {
                if (!hasPermission(p, SculptPermissions.BLUEPRINT_PUBLISH)) {
                    MessageUtil.sendTranslated(p, K + "save.no_public_perm"); return true;
                }
                isPublic = true;
            }
        }
        if (!bpManager.hasSelection(p)) {
            MessageUtil.sendTranslated(p, K + "save.no_selection"); return true;
        }
        String err = bpManager.saveBlueprint(p, name, isPublic, folder);
        if (err != null) { MessageUtil.sendTranslated(p, err); /* err is already a lang key */ }
        else { MessageUtil.sendTranslated(p, K + "save.success", name); }
        return true;
    }

    // ====================== list ======================
    private boolean handleList(Player p, String[] a) {
        boolean isPublic = false; String folder = null; int page = 1;
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--public" -> isPublic = true;
                case "--page" -> {
                    if (i + 1 < a.length) {
                        Integer parsed = parseInteger(a[++i]);
                        if (parsed == null) {
                            MessageUtil.sendTranslated(p, K + "invalid_argument", "--page");
                            return true;
                        }
                        page = parsed;
                    }
                }
                default -> folder = a[i];
            }
        }
        try {
            if (!isPublic) {
                // Show sub-folders at this level first
                var subFolders = bpManager.folderManager().listFolders(p.getUniqueId(), folder, false);
                if (!subFolders.isEmpty()) {
                    MessageUtil.sendTranslated(p, K + "list.folder_header");
                    int fn = 1;
                    for (var f : subFolders) {
                        String path = folder != null ? folder + "/" + f.name() : f.name();
                        // Clickable folder entry — clicking drills into this folder
                        MessageUtil.sendTranslated(p, K + "list.folder_entry", fn, path);
                        fn++;
                    }
                }
            }
            if (isPublic) {
                sendPaginatedList(p, bpManager.listPublicBlueprints(),
                    K + "list.header_public", K + "list.empty_public",
                    "/sculpt blueprint list --public", page);
            } else {
                sendPaginatedList(p, bpManager.listBlueprints(p.getUniqueId(), false, folder),
                    K + "list.header_private", K + "list.empty_private",
                    "/sculpt blueprint list", page);
            }
        } catch (IOException e) { MessageUtil.sendTranslated(p, K + "list.read_error", e.getMessage()); }
        return true;
    }

    private record ListEntry(String name, int gridN, String blockKey) {}
    private List<ListEntry> toListEntries(java.util.Collection<?> items) {
        return items.stream().map(item -> {
            if (item instanceof BlueprintManager.BlueprintSummary b) {
                return new ListEntry(b.name(), b.gridN(), b.blockKey() != null ? b.blockKey() : "mixed");
            }
            if (item instanceof BlueprintManager.PublicBlueprintEntry e) {
                return new ListEntry(e.name(), e.gridN(), e.blockKey() != null ? e.blockKey() : "mixed");
            }
            throw new IllegalArgumentException("unknown type: " + item.getClass());
        }).toList();
    }

    private void sendPaginatedList(Player p, java.util.Collection<?> rawItems,
                                   String headerKey, String emptyKey,
                                   String commandBase, int page) {
        var entries = toListEntries(rawItems);
        if (entries.isEmpty()) { MessageUtil.sendTranslated(p, emptyKey); return; }
        int totalPages = (entries.size() + MessageUtil.PAGE_SIZE - 1) / MessageUtil.PAGE_SIZE;
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;
        int from = (page - 1) * MessageUtil.PAGE_SIZE;
        int to = Math.min(from + MessageUtil.PAGE_SIZE, entries.size());
        MessageUtil.sendTranslated(p, headerKey, entries.size());
        int entryN = from + 1;
        for (var e : entries.subList(from, to)) {
            MessageUtil.sendTranslated(p, K + "list.entry", entryN, e.name(), e.gridN(), e.blockKey());
            entryN++;
        }
        MessageUtil.sendPageBar(p, commandBase, page, totalPages);
    }

    // ====================== delete ======================
    private boolean handleDelete(Player p, String[] a) {
        if (a.length < 1) { MessageUtil.sendTranslated(p, K + "delete.usage"); return true; }
        try {
            BlueprintData data = loadBlueprint(p, a[0]);
            if (data == null) { MessageUtil.sendTranslated(p, K + "delete.not_found", a[0]); return true; }
            boolean pub = data.visibility() == BlueprintData.Visibility.PUBLIC;
            bpManager.deleteBlueprint(p.getUniqueId(), data.blueprintId(), pub);
            MessageUtil.sendTranslated(p, K + "delete.success", data.name());
        } catch (IOException e) { MessageUtil.sendTranslated(p, K + "delete.failed", e.getMessage()); }
        return true;
    }

    private boolean handleRename(Player p, String[] a) {
        if (a.length < 2) { MessageUtil.sendTranslated(p, K + "rename.usage"); return true; }
        BlueprintData data = loadBlueprint(p, a[0]);
        if (data == null) { MessageUtil.sendTranslated(p, K + "rename.not_found", a[0]); return true; }
        String newName = a[1];
        boolean pub = data.visibility() == BlueprintData.Visibility.PUBLIC;
        String err = bpManager.renameBlueprint(p.getUniqueId(), data.blueprintId(), newName, pub);
        if (err != null) { MessageUtil.sendTranslated(p, err); }
        else { MessageUtil.sendTranslated(p, K + "rename.success", newName); }
        return true;
    }

    // ====================== download ======================
    private boolean handleDownload(Player p, String[] a) {
        if (a.length < 1) { MessageUtil.sendTranslated(p, K + "download.usage"); return true; }
        MessageUtil.sendTranslated(p, K + "download.started");
        bpManager.downloadBlueprint(p, a[0]).whenComplete((error, failure) ->
            FoliaScheduler.runEntityTask(plugin, p, () -> {
                if (!p.isOnline()) return;
                if (failure != null) MessageUtil.sendTranslated(p, K + "download.failed");
                else if (error != null) MessageUtil.sendTranslated(p, error);
                else MessageUtil.sendTranslated(p, K + "download.success");
            }));
        return true;
    }

    // ====================== bind / unbind ======================
    private boolean handleBind(Player p, String[] a) {
        if (a.length < 1) { MessageUtil.sendTranslated(p, K + "bind.usage"); return true; }
        BlueprintData data = loadBlueprint(p, a[0]);
        if (data == null) { MessageUtil.sendTranslated(p, K + "bind.not_found", a[0]); return true; }
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType().isAir()) { MessageUtil.sendTranslated(p, K + "bind.empty_hand"); return true; }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) { MessageUtil.sendTranslated(p, K + "bind.cannot_modify"); return true; }
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "blueprint_id"), PersistentDataType.STRING, data.blueprintId().toString());
        for (int i = 1; i < a.length; i++) {
            switch (a[i]) {
                case "--pasteAir" -> writePdcBool(meta, "paste_air", true);
                case "--no-pasteAir" -> writePdcBool(meta, "paste_air", false);
                case "--overwrite" -> writePdcBool(meta, "overwrite", true);
                case "--adhesive" -> writePdcBool(meta, "adhesive", true);
                case "--rotate" -> { if (i + 1 < a.length) writePdcString(meta, "rotate_mode", a[++i]); }
                case "--ry" -> {
                    if (i + 1 < a.length) {
                        Integer value = parseQuarterTurn(a[++i]);
                        if (value == null) {
                            MessageUtil.sendTranslated(p, K + "invalid_argument", "--ry");
                            return true;
                        }
                        writePdcInt(meta, "ry", value);
                    }
                }
                case "--flip" -> { if (i + 1 < a.length) writePdcString(meta, "flip", a[++i]); }
            }
        }
        // Set display name and lore from language files (player's locale at bind time)
        LanguageManager lang = plugin.getLanguageManager();
        MiniMessage mm = MiniMessage.miniMessage();
        String nameStr = lang.getMessage(p, K + "bind.item_name", data.name());
        meta.displayName(mm.deserialize(nameStr));
        String bk = data.blockKey() != null ? data.blockKey() : "mixed";
        List<String> loreStrs = lang.getStringList(p, K + "bind.item_lore");
        List<Component> loreComponents = loreStrs.stream()
            .map(s -> mm.deserialize(MessageFormat.format(s, data.gridN(), bk)))
            .toList();
        meta.lore(loreComponents);
        item.setItemMeta(meta);
        MessageUtil.sendTranslated(p, K + "bind.success", data.name(), item.getType().toString());
        return true;
    }

    private boolean handleUnbind(Player p, String[] a) {
        ItemStack item = p.getInventory().getItemInMainHand();
        if (!item.hasItemMeta()) { MessageUtil.sendTranslated(p, K + "unbind.not_bound"); return true; }
        ItemMeta meta = item.getItemMeta();
        String[] keys = {"blueprint_id","paste_air","overwrite","adhesive","rotate_mode","ry","flip"};
        boolean had = false;
        for (String k : keys) {
            var nsk = new org.bukkit.NamespacedKey(plugin, k);
            if (meta.getPersistentDataContainer().has(nsk, PersistentDataType.STRING)
                || meta.getPersistentDataContainer().has(nsk, PersistentDataType.BOOLEAN)
                || meta.getPersistentDataContainer().has(nsk, PersistentDataType.INTEGER)) {
                meta.getPersistentDataContainer().remove(nsk); had = true;
            }
        }
        if (had) {
            meta.displayName(null);
            meta.lore(List.of());
            item.setItemMeta(meta);
            MessageUtil.sendTranslated(p, K + "unbind.success");
        }
        else { MessageUtil.sendTranslated(p, K + "unbind.not_bound"); }
        return true;
    }

    // ====================== give ======================
    private boolean handleGive(Player p, String[] a) {
        if (a.length < 1) { MessageUtil.sendTranslated(p, K + "give.usage"); return true; }
        BlueprintData data = loadBlueprint(p, a[0]);
        if (data == null) { MessageUtil.sendTranslated(p, K + "give.not_found", a[0]); return true; }
        p.getInventory().addItem(bpManager.createBlueprintItem(data.blueprintId()));
        MessageUtil.sendTranslated(p, K + "give.success", data.name());
        return true;
    }

    private boolean handleSettings(Player p, String[] a) {
        if (a.length == 0) {
            // 顯示當前設定
            PasteSettings cur = bpManager.getPlayerSettings(p.getUniqueId());
            MessageUtil.sendTranslated(p, K + "settings.header");
            MessageUtil.sendTranslated(p, K + "settings.pasteAir", cur.pasteAir());
            MessageUtil.sendTranslated(p, K + "settings.overwriteCells", cur.overwriteCells());
            MessageUtil.sendTranslated(p, K + "settings.overwriteBlocks", cur.overwriteBlocks());
            MessageUtil.sendTranslated(p, K + "settings.adhesive", cur.adhesive());
            MessageUtil.sendTranslated(p, K + "settings.rotateMode", cur.rotateMode());
            MessageUtil.sendTranslated(p, K + "settings.ry", cur.ry());
            MessageUtil.sendTranslated(p, K + "settings.usage");
            return true;
        }
        PasteSettings cur = bpManager.getPlayerSettings(p.getUniqueId());
        Boolean pasteAir = null; Boolean overwrite = null; Boolean adhesive = null;
        String rotateMode = null; Integer ry = null;
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--pasteAir" -> pasteAir = true;
                case "--no-pasteAir" -> pasteAir = false;
                case "--overwrite" -> overwrite = true;
                case "--no-overwrite" -> overwrite = false;
                case "--adhesive" -> adhesive = true;
                case "--no-adhesive" -> adhesive = false;
                case "--rotate" -> { if (i + 1 < a.length) rotateMode = a[++i]; }
                case "--ry" -> {
                    if (i + 1 < a.length) {
                        ry = parseQuarterTurn(a[++i]);
                        if (ry == null) {
                            MessageUtil.sendTranslated(p, K + "invalid_argument", "--ry");
                            return true;
                        }
                    }
                }
            }
        }
        PasteSettings.RotateMode parsedMode = cur.rotateMode();
        if (rotateMode != null) {
            try {
                parsedMode = PasteSettings.RotateMode.valueOf(rotateMode.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                MessageUtil.sendTranslated(p, K + "invalid_argument", "--rotate");
                return true;
            }
        }
        // Merge: keep current values if not overridden
        PasteSettings merged = new PasteSettings(
            pasteAir != null ? pasteAir : cur.pasteAir(),
            overwrite != null ? overwrite : cur.overwriteCells(),
            overwrite != null ? overwrite : cur.overwriteBlocks(),
            adhesive != null ? adhesive : cur.adhesive(),
            parsedMode,
            ry != null ? ry : cur.ry(),
            cur.flipAxis()
        );
        bpManager.setPlayerSettings(p.getUniqueId(), merged);
        MessageUtil.sendTranslated(p, K + "settings.saved");
        return true;
    }

    // ====================== publish ======================
    private boolean handlePublish(Player p, String[] a) {
        if (a.length < 1) { MessageUtil.sendTranslated(p, K + "publish.usage"); return true; }
        BlueprintData data = loadBlueprint(p, a[0]);
        if (data == null) { MessageUtil.sendTranslated(p, K + "publish.not_found", a[0]); return true; }
        BlueprintData.Visibility vis = BlueprintData.Visibility.PUBLIC;
        String desc = null;
        for (int i = 1; i < a.length; i++) {
            switch (a[i]) {
                case "--visibility" -> { if (i + 1 < a.length) try { vis = BlueprintData.Visibility.valueOf(a[++i].toUpperCase()); } catch (IllegalArgumentException e) {} }
                case "--description" -> { if (i + 1 < a.length) desc = a[++i]; }
            }
        }
        final BlueprintData.Visibility selectedVisibility = vis;
        MessageUtil.sendTranslated(p, K + "publish.started");
        bpManager.publishBlueprint(p, data, selectedVisibility, desc).whenComplete((result, failure) ->
            FoliaScheduler.runEntityTask(plugin, p, () -> {
                if (!p.isOnline()) return;
                if (failure != null || result == null) {
                    MessageUtil.sendTranslated(p, K + "publish.network_error");
                } else if (result.error() != null) {
                    MessageUtil.sendTranslated(p, result.error());
                } else {
                    MessageUtil.sendTranslated(p, K + "publish.success");
                    if (result.shareUrl() != null) {
                        MessageUtil.sendTranslated(p, K + "publish.share_url", result.shareUrl());
                    }
                    MessageUtil.sendTranslated(p, K + "publish.visibility", selectedVisibility.name());
                    if (result.managementKeyStored()) {
                        MessageUtil.sendTranslated(p, K + "publish.key_saved");
                        if (hasPermission(p, SculptPermissions.BLUEPRINT_UNPUBLISH)) {
                            MessageUtil.sendTranslated(
                                p, K + "publish.unpublish_hint", data.blueprintId());
                        }
                    }
                    if (result.accessPassword() != null) {
                        MessageUtil.sendTranslated(p, K + "publish.access_password", result.accessPassword());
                    }
                }
            }));
        return true;
    }

    // ====================== unpublish ======================
    private boolean handleUnpublish(Player p, String[] a) {
        if (a.length < 1) {
            MessageUtil.sendTranslated(p, K + "unpublish.usage");
            return true;
        }
        MessageUtil.sendTranslated(p, K + "unpublish.started");
        bpManager.unpublishBlueprint(p.getUniqueId(), a[0]).whenComplete((result, failure) ->
            FoliaScheduler.runEntityTask(plugin, p, () -> {
                if (!p.isOnline()) return;
                if (failure != null || result == null) {
                    MessageUtil.sendTranslated(p, K + "unpublish.network_error");
                } else if (result.error() != null) {
                    if ((K + "unpublish.not_found").equals(result.error())) {
                        MessageUtil.sendTranslated(p, result.error(), result.name());
                    } else {
                        MessageUtil.sendTranslated(p, result.error());
                    }
                } else if (result.alreadyAbsent()) {
                    MessageUtil.sendTranslated(
                        p, K + "unpublish.already_absent", result.name());
                } else {
                    MessageUtil.sendTranslated(p, K + "unpublish.success", result.name());
                }
            }));
        return true;
    }

    // ====================== export ======================
    private boolean handleExport(Player p, String[] a) {
        if (a.length < 1) { MessageUtil.sendTranslated(p, K + "export.usage"); return true; }
        BlueprintData data = loadBlueprint(p, a[0]);
        if (data == null) { MessageUtil.sendTranslated(p, K + "export.not_found", a[0]); return true; }
        String err = bpManager.exportBlueprint(p, data);
        if (err != null) { MessageUtil.sendTranslated(p, err); }
        else { MessageUtil.sendTranslated(p, K + "export.success", data.name()); }
        return true;
    }

    // ====================== import ======================
    private boolean handleImport(Player p, String[] a) {
        // --page flag: list export files with pagination
        if (a.length >= 2 && "--page".equals(a[0])) {
            int ipage = 1;
            try { ipage = Integer.parseInt(a[1]); } catch (NumberFormatException ignored) {}
            listExportFiles(p, ipage);
            return true;
        }
        if (a.length < 1) {
            listExportFiles(p, 1);
            return true;
        }
        String err = bpManager.importBlueprint(p, a[0]);
        if (err != null) { MessageUtil.sendTranslated(p, err); }
        else { MessageUtil.sendTranslated(p, K + "import.success", a[0]); }
        return true;
    }

    private void listExportFiles(Player p, int page) {
        var files = bpManager.listExportFiles();
        if (files.isEmpty()) { MessageUtil.sendTranslated(p, K + "import.no_files"); return; }
        int totalPages = (files.size() + MessageUtil.PAGE_SIZE - 1) / MessageUtil.PAGE_SIZE;
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;
        int from = (page - 1) * MessageUtil.PAGE_SIZE;
        int to = Math.min(from + MessageUtil.PAGE_SIZE, files.size());
        MessageUtil.sendTranslated(p, K + "import.available");
        for (String f : files.subList(from, to))
            MessageUtil.sendMessage(p, " <gray>- <white>" + f);
        MessageUtil.sendPageBar(p, "/sculpt blueprint import", page, totalPages);
    }

    // ====================== Tab Complete ======================
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length <= 2) return filter(a.length == 2 ? a[1] : "", allowedSubcommands(s));

        Player player = s instanceof Player ? (Player) s : null;
        String sub = a[1].toLowerCase(Locale.ROOT);

        if (a.length == 3) {
            switch (sub) {
                case "delete", "bind", "give", "export", "publish":
                    return completeBlueprintName(a[2], player);
                case "unpublish":
                    return completePublishedBlueprintName(a[2], player);
                case "rename":
                    return completeBlueprintName(a[2], player);
                case "list": {
                    var listFlags = new java.util.ArrayList<>(completeFolderPath(a[2], player));
                    listFlags.addAll(filter(a[2], java.util.List.of("--page", "--public")));
                    return listFlags;
                }
                case "import": {
                    var importFlags = new java.util.ArrayList<>(filter(a[2], bpManager.listExportFiles()));
                    importFlags.addAll(filter(a[2], java.util.List.of("--page")));
                    return importFlags;
                }
            }
        }

        if ("bind".equals(sub) && a.length > 3) return completeFlag(a);
        if ("publish".equals(sub) && a.length > 3) return completePublishFlag(a);
        if ("settings".equals(sub)) return completeSettingsFlag(a);

        if ("save".equals(sub) && a.length >= 3) {
            String last = a[a.length - 1];
            // a.length == 3: completing the name — suggest folder paths with trailing /
            if (a.length == 3) {
                return completeFolderPath(last, player);
            }
            // a.length >= 4: completing flags — suggest --public
            return hasPermission(s, SculptPermissions.BLUEPRINT_PUBLISH)
                ? filter(last, List.of("--public"))
                : List.of();
        }

        return List.of();
    }

    private List<String> completePublishFlag(String[] a) {
        String last = a[a.length - 1];
        List<String> flags = List.of("--visibility", "--description");
        if (a.length > 2) {
            String prev = a[a.length - 2];
            if ("--visibility".equals(prev)) return filter(last, VISIBILITY_VALUES);
        }
        return filter(last, flags);
    }

    private List<String> completeSettingsFlag(String[] a) {
        String last = a[a.length - 1];
        List<String> flags = List.of("--pasteAir","--no-pasteAir","--overwrite","--no-overwrite","--adhesive","--no-adhesive","--rotate","--ry");
        if (a.length > 2) {
            String prev = a[a.length - 2];
            if ("--rotate".equals(prev)) return filter(last, ROTATE_MODES);
            if ("--ry".equals(prev)) return filter(last, RY_VALUES);
        }
        return filter(last, flags);
    }

    private List<String> completeFlag(String[] a) {
        String last = a[a.length - 1];
        List<String> flags = List.of("--pasteAir","--no-pasteAir","--overwrite","--adhesive","--rotate","--ry","--flip","--at");
        if (a.length > 2) {
            String prev = a[a.length - 2];
            if ("--rotate".equals(prev)) return filter(last, ROTATE_MODES);
            if ("--ry".equals(prev)) return filter(last, RY_VALUES);
            if ("--flip".equals(prev)) return filter(last, FLIP_AXES);
            if ("--at".equals(prev)) return List.of("<x> <y> <z>");
        }
        return filter(last, flags);
    }

    // ====================== helpers ======================
    private void sendUsage(Player p) {
        MessageUtil.sendTranslated(p, K + "usage_header");
        for (String sub : allowedSubcommands(p)) {
            final String usageKey = switch (sub) {
                case "save", "list", "delete", "bind", "download",
                     "publish", "unpublish", "export", "import" -> "usage_" + sub;
                default -> null;
            };
            if (usageKey != null) MessageUtil.sendTranslated(p, K + usageKey);
        }
    }

    @Nullable private BlueprintData loadBlueprint(Player p, String nameOrId) {
        UUID uid = p.getUniqueId();
        try {
            UUID id; try { id = UUID.fromString(nameOrId); } catch (IllegalArgumentException e) { id = null; }
            if (id != null) {
                BlueprintData d = bpManager.io().readBlueprint(uid, id, false);
                if (d != null) return d;
                d = bpManager.io().readBlueprint(uid, id, true);
                if (d != null) return d;
            }
            var list = bpManager.listAllBlueprints(uid, false);
            for (var b : list) if (b.name().equals(nameOrId))
                return bpManager.io().readBlueprint(uid, b.blueprintId(), false);
            list = bpManager.listAllBlueprints(uid, true);
            for (var b : list) if (b.name().equals(nameOrId))
                return bpManager.io().readBlueprint(uid, b.blueprintId(), true);
        } catch (IOException ignored) {}
        return null;
    }

    private void writePdcBool(ItemMeta m, String k, boolean v) { m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, k), PersistentDataType.STRING, String.valueOf(v)); }
    private void writePdcString(ItemMeta m, String k, String v) { m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, k), PersistentDataType.STRING, v); }
    private void writePdcInt(ItemMeta m, String k, int v) { m.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, k), PersistentDataType.INTEGER, v); }

    @Nullable
    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Integer parseQuarterTurn(String value) {
        Integer parsed = parseInteger(value);
        if (parsed == null) return null;
        int normalized = Math.floorMod(parsed, 360);
        return normalized % 90 == 0 ? normalized : null;
    }

    private List<String> filter(String input, List<String> candidates) {
        return candidates.stream().filter(s -> s.toLowerCase().startsWith(input.toLowerCase())).collect(Collectors.toList());
    }

    private static List<String> allowedSubcommands(CommandSender sender) {
        return allowedSubcommands(sender::hasPermission);
    }

    static List<String> allowedSubcommands(Predicate<String> permissionChecker) {
        return BLUEPRINT_SUBCOMMANDS.stream()
            .filter(sub -> hasPermission(permissionChecker,
                SculptPermissions.blueprint(sub)))
            .toList();
    }

    private static boolean hasPermission(CommandSender sender, String permission) {
        return hasPermission(sender::hasPermission, permission);
    }

    private static boolean hasPermission(Predicate<String> permissionChecker,
                                         String permission) {
        return permissionChecker.test(permission);
    }

    private List<String> completeBlueprintName(String input, Player player) {
        if (player == null) return List.of();
        try {
            List<String> names = new ArrayList<>();
            var privateBps = bpManager.listAllBlueprints(player.getUniqueId(), false);
            for (var bp : privateBps) names.add(bp.name());
            var publicBps = bpManager.listAllBlueprints(player.getUniqueId(), true);
            for (var bp : publicBps) names.add(bp.name());
            String stripped = input.startsWith("\"") ? input.substring(1) : input;
            return filter(stripped, names).stream().map(BlueprintCommand::quoteIfNeeded).collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<String> completePublishedBlueprintName(String input, Player player) {
        if (player == null) return List.of();
        String stripped = input.startsWith("\"") ? input.substring(1) : input;
        return filter(stripped, bpManager.publishedBlueprintNames(player.getUniqueId())).stream()
            .map(BlueprintCommand::quoteIfNeeded)
            .collect(Collectors.toList());
    }

    private List<String> completeFolderPath(String input, Player player) {
        if (player == null) return List.of();
        try {
            List<String> paths = new ArrayList<>();
            collectFolderPaths(player.getUniqueId(), null, "", paths);
            // Also add folder paths with trailing / for drilling
            List<String> withSlash = new ArrayList<>();
            for (var p : paths) {
                withSlash.add(p + "/");
            }
            String stripped = input.startsWith("\"") ? input.substring(1) : input;
            return filter(stripped, withSlash).stream().map(BlueprintCommand::quoteIfNeeded).collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private void collectFolderPaths(UUID playerUuid, String parentPath, String prefix, List<String> out) throws IOException {
        var folders = bpManager.folderManager().listFolders(playerUuid, parentPath, false);
        for (var folder : folders) {
            String path = prefix.isEmpty() ? folder.name() : prefix + "/" + folder.name();
            out.add(path);
            collectFolderPaths(playerUuid, path, path, out);
        }
    }

    private static String quoteIfNeeded(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c > 127) {
                return "\"" + s + "\"";
            }
        }
        return s;
    }
}
