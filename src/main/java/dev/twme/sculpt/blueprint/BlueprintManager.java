package dev.twme.sculpt.blueprint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.HeadResolver;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.util.FoliaRegionGuard;
import dev.twme.sculpt.util.PrivateItemDisplay;

/**
 * BlueprintManager — 藍圖系統核心。
 * <p>
 * 協調藍圖的儲存、載入、貼上、管理與下載功能。
 * 整合 BlueprintIO、FolderManager 與 PasteEngine。
 */
public class BlueprintManager {

    private static final String PDC_BLUEPRINT_ID = "sculpt:blueprint_id";
    private static final String PDC_PASTE_AIR = "sculpt:paste_air";
    private static final String PDC_OVERWRITE = "sculpt:overwrite";
    private static final String PDC_ADHESIVE = "sculpt:adhesive";
    private static final String PDC_ROTATE_MODE = "sculpt:rotate_mode";
    private static final String PDC_RY = "sculpt:ry";
    private static final String PDC_FLIP = "sculpt:flip";

    private static final int DEFAULT_MAX_BLUEPRINTS_PER_PLAYER = 100;
    private static final int DEFAULT_MAX_DOWNLOAD_SIZE = 12_582_912;
    private static final int MAX_HTTP_RESPONSE_SIZE = 12_582_912;

    private final Sculpt plugin;
    private final BlueprintIO io;
    private final FolderManager folderManager;
    private final PasteEngine pasteEngine;
    private final HttpClient httpClient;

    private static final String K = "command.sculpt.blueprint.";

    /** Per-player transient selection state. */
    private final Map<UUID, SculptBlock> selectedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, SelectionBounds> selectedRegions = new ConcurrentHashMap<>();
    private final Map<UUID, Location> regionFirstCorners = new ConcurrentHashMap<>();
    private final Map<UUID, SelectionMode> selectionModes = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedReferenceFacings = new ConcurrentHashMap<>();

    /** 每個玩家選取範圍的視覺高亮顯示實體。 */
    private final Map<UUID, PrivateItemDisplay> selectionDisplays = new ConcurrentHashMap<>();

    public BlueprintManager(Sculpt plugin) {
        this.plugin = plugin;
        Path blueprintsDir = plugin.getDataFolder().toPath().resolve("blueprints");
        this.io = new BlueprintIO(blueprintsDir);
        this.folderManager = new FolderManager(io,
            plugin.getConfig().getInt("blueprint.storage.maxFolderDepth", 3));
        this.pasteEngine = new PasteEngine(plugin);
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("blueprint.enabled", true);
    }

    /** Apply settings held by long-lived blueprint helper objects. */
    public void reloadConfig() {
        folderManager.setMaxDepth(
            plugin.getConfig().getInt("blueprint.storage.maxFolderDepth", 3));
    }

    public PasteSettings defaultPasteSettings() {
        String root = "blueprint.pasteDefaults.";
        PasteSettings.RotateMode mode;
        try {
            mode = PasteSettings.RotateMode.valueOf(
                plugin.getConfig().getString(root + "rotateMode", "auto")
                    .toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            mode = PasteSettings.RotateMode.AUTO;
        }
        int ry = plugin.getConfig().getInt(root + "rotationY", 0);
        ry = Math.floorMod(ry, 360);
        if (ry % 90 != 0) ry = 0;
        String flip = plugin.getConfig().getString(root + "flip", null);
        if (flip != null && !List.of("x", "y", "z")
                .contains(flip.toLowerCase(java.util.Locale.ROOT))) {
            flip = null;
        }
        return new PasteSettings(
            plugin.getConfig().getBoolean(root + "pasteAir", true),
            plugin.getConfig().getBoolean(root + "overwriteCells", true),
            plugin.getConfig().getBoolean(root + "overwriteBlocks", true),
            plugin.getConfig().getBoolean(root + "adhesive", false),
            mode, ry, flip);
    }

    // ====================== 儲存藍圖 ======================

    /**
     * 儲存玩家目前選取的單顆或長方體 Sculpt 藍圖。
     *
     * @param player   玩家
     * @param name     藍圖名稱
     * @param isPublic 是否存入公共目錄
     * @param folder   目標資料夾路徑（可選，如 "castle/medieval"）
     * @return 錯誤訊息（null = 成功）
     */
    @Nullable
    public String saveBlueprint(Player player, String name,
                                boolean isPublic, @Nullable String folder) {
        if (name == null || name.isBlank() || name.length() > 64) {
            return K + "save.invalid_name";
        }
        String accessError = selectionAccessError(player);
        if (accessError != null) return accessError;
        BlueprintData data = createBlueprintFromSelection(
            player, name,
            isPublic ? BlueprintData.Visibility.PUBLIC : BlueprintData.Visibility.PRIVATE);
        if (data == null) {
            return hasSelection(player) ? K + "save.empty_selection" : K + "save.no_selection";
        }
        // 檢查藍圖數量上限
        UUID playerUuid = player.getUniqueId();
        try {
            int count = countBlueprints(playerUuid, isPublic);
            int maximum = plugin.getConfig().getInt(
                "blueprint.storage.maxPerPlayer", DEFAULT_MAX_BLUEPRINTS_PER_PLAYER);
            if (maximum > 0 && count >= maximum) {
                return K + "save.limit_reached";
            }
        } catch (IOException e) {
            return K + "save.write_error";
        }

        // 寫入檔案
        try {
            io.writeBlueprint(playerUuid, data, isPublic);
            if (folder != null && !folder.isBlank()) {
                folderManager.assignBlueprint(
                    playerUuid, data.blueprintId(), folder, isPublic);
            }
            io.updatePlayerIndex(playerUuid, player.getName());

            // 更新 public-index.json
            if (isPublic) {
                updatePublicIndexAdd(data, playerUuid, player.getName());
            }

        } catch (IOException | IllegalArgumentException e) {
            try {
                io.deleteBlueprint(playerUuid, data.blueprintId(), isPublic);
                folderManager.removeBlueprint(playerUuid, data.blueprintId(), isPublic);
            } catch (IOException ignored) {}
            return K + "save.write_error";
        }

        return null; // 成功
    }

    // ====================== 貼上藍圖 ======================

    /**
     * 將藍圖貼上到目標位置。
     *
     * @param player   玩家
     * @param data     藍圖資料
     * @param location 目標位置
     * @param settings 貼上設定
     * @return 錯誤訊息（null = 成功）
     */
    @Nullable
    public String pasteBlueprint(Player player, BlueprintData data,
                                  Location location, PasteSettings settings) {
        String result = pasteEngine.paste(player, data, location, settings);
        // PasteEngine may return null (success) or a formatted message
        return result;
    }

    /** Paste overload used by click interactions that can supply a face orientation. */
    @Nullable
    public String pasteBlueprint(Player player, BlueprintData data, Location location,
                                  PasteSettings settings, @Nullable BlockFace clickedFace) {
        return pasteEngine.paste(player, data, location, settings, clickedFace);
    }

    // ====================== 列出藍圖 ======================

    /**
     * 列出玩家的藍圖。
     *
     * @param playerUuid 玩家 UUID
     * @param isPublic   是否列出公共目錄
     * @param folder     資料夾路徑（可選）
     * @return 藍圖摘要列表
     */
    public List<BlueprintSummary> listBlueprints(UUID playerUuid, boolean isPublic,
                                                  @Nullable String folder) throws IOException {
        Path blueprintsDir = isPublic
            ? io.publicPlayerDir(playerUuid).resolve("blueprints")
            : io.privatePlayerDir(playerUuid).resolve("blueprints");

        if (!Files.exists(blueprintsDir)) return List.of();

        List<BlueprintSummary> result = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(blueprintsDir, "*.blueprint")) {
            for (Path file : stream) {
                try {
                    BlueprintData data = io.readBlueprintFrom(file);
                    if (data != null && folderManager.isBlueprintInFolder(
                            playerUuid, data.blueprintId(), folder, isPublic)) {
                        result.add(new BlueprintSummary(
                            data.blueprintId(),
                            data.name(),
                            data.blockKey(),
                            data.gridN(),
                            data.createdTimestamp(),
                            data.visibility()
                        ));
                    }
                } catch (IOException ignored) {}
            }
        }
        return result;
    }

    /** List every blueprint regardless of logical folder, for ID/name lookup and completion. */
    public List<BlueprintSummary> listAllBlueprints(UUID playerUuid, boolean isPublic)
            throws IOException {
        Path directory = io.blueprintsDirFor(playerUuid, isPublic);
        if (!Files.exists(directory)) return List.of();
        List<BlueprintSummary> result = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(directory, "*.blueprint")) {
            for (Path file : stream) {
                try {
                    BlueprintData data = io.readBlueprintFrom(file);
                    if (data != null) {
                        result.add(new BlueprintSummary(data.blueprintId(), data.name(),
                            data.blockKey(), data.gridN(), data.createdTimestamp(), data.visibility()));
                    }
                } catch (IOException ignored) {}
            }
        }
        return result;
    }

    /**
     * 列出所有公開藍圖（跨玩家，從 public-index.json）。
     */
    public List<PublicBlueprintEntry> listPublicBlueprints() throws IOException {
        BlueprintIO.PublicIndex index = io.readPublicIndex();
        List<PublicBlueprintEntry> result = new ArrayList<>();
        if (index.blueprints != null) {
            for (BlueprintIO.PublicIndex.PublicEntry entry : index.blueprints) {
                result.add(new PublicBlueprintEntry(
                    UUID.fromString(entry.blueprintId),
                    entry.name,
                    entry.submitterUUID != null ? UUID.fromString(entry.submitterUUID) : null,
                    entry.submitterName,
                    entry.gridN,
                    entry.blockKey,
                    entry.createdTimestamp
                ));
            }
        }
        return result;
    }

    // ====================== 刪除藍圖 ======================

    /**
     * 刪除藍圖。
     */
    public boolean deleteBlueprint(UUID playerUuid, UUID blueprintId,
                                    boolean isPublic) throws IOException {
        boolean deleted = io.deleteBlueprint(playerUuid, blueprintId, isPublic);
        if (deleted) folderManager.removeBlueprint(playerUuid, blueprintId, isPublic);
        if (deleted && isPublic) {
            updatePublicIndexRemove(blueprintId);
        }
        return deleted;
    }

    // ====================== 重新命名 ======================

    /**
     * 重新命名藍圖。
     *
     * @param playerUuid 玩家 UUID
     * @param blueprintId 藍圖 ID
     * @param newName     新名稱
     * @param isPublic    是否為公開藍圖
     * @return 錯誤訊息（null = 成功）
     */
    @Nullable
    public String renameBlueprint(UUID playerUuid, UUID blueprintId, String newName, boolean isPublic) {
        if (newName == null || newName.isBlank() || newName.length() > 64) {
            return K + "rename.invalid_name";
        }
        try {
            io.renameBlueprint(playerUuid, blueprintId, newName, isPublic);
            // 更新 public-index.json 中的名稱
            if (isPublic) {
                BlueprintIO.PublicIndex index = io.readPublicIndex();
                if (index.blueprints != null) {
                    for (BlueprintIO.PublicIndex.PublicEntry entry : index.blueprints) {
                        if (entry.blueprintId.equals(blueprintId.toString())) {
                            entry.name = newName;
                            break;
                        }
                    }
                    index.lastUpdated = System.currentTimeMillis();
                    io.writePublicIndex(index);
                }
            }
            try {
                io.publications().rename(playerUuid, blueprintId, newName);
            } catch (IOException e) {
                plugin.getLogger().warning(
                    "Could not update the saved SculptWeb publication name for "
                    + blueprintId + ": " + rootMessage(e));
            }
            return null;
        } catch (IOException e) {
            return K + "rename.failed";
        }
    }

    // ====================== 下載藍圖 ======================

    /**
     * 從 SculptWeb 連結下載藍圖。
     *
     * @param player 玩家
     * @param url    藍圖分享連結
     * @return 錯誤訊息（null = 成功）
     */
    public CompletableFuture<String> downloadBlueprint(Player player, String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(K + "download.invalid_url");
        }

        String host = normalizedHttpsHost(uri);
        if (host == null) {
            return CompletableFuture.completedFuture(K + "download.invalid_url");
        }
        if (!isAllowedDownloadDomain(host)) {
            return CompletableFuture.completedFuture(K + "download.domain_not_allowed");
        }

        String path = uri.getPath();
        UUID blueprintId = extractBlueprintId(path);
        if (blueprintId == null) {
            return CompletableFuture.completedFuture(K + "download.invalid_url");
        }

        final URI downloadUri;
        try {
            downloadUri = new URI("https", null, host, -1,
                "/api/blueprints/" + blueprintId + "/download", null, null);
        } catch (URISyntaxException e) {
            return CompletableFuture.completedFuture(K + "download.invalid_url");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(downloadUri)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET();
        String token = queryParameter(uri, "token");
        if (token != null) {
            if (token.length() > 512 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
                return CompletableFuture.completedFuture(K + "download.invalid_url");
            }
            request.header("Authorization", "Bearer " + token);
        }

        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        int maxDownloadSize = plugin.getConfig().getInt(
            "blueprint.download.maxBytes", DEFAULT_MAX_DOWNLOAD_SIZE);
        maxDownloadSize = Math.max(1, Math.min(maxDownloadSize, MAX_HTTP_RESPONSE_SIZE));
        final int responseLimit = maxDownloadSize;

        return httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
            .thenApply(response -> processDownloadResponse(
                response, responseLimit, playerUuid, playerName, host))
            .exceptionally(error -> {
                plugin.getLogger().warning("Blueprint download failed for " + playerUuid
                    + ": " + rootMessage(error));
                return K + "download.failed";
            });
    }

    // ====================== 發布藍圖到 SculptWeb ======================

    /**
     * 發布藍圖到 SculptWeb API。
     *
     * @param player     玩家
     * @param data       藍圖資料
     * @param visibility 網站可見性 (PUBLIC / UNLIST / SECRET)
     * @param description 描述文字（可選）
     * @return PublishResult（成功時 error=null），或 lang key 錯誤
     */
    public CompletableFuture<PublishResult> publishBlueprint(
            Player player, BlueprintData data, BlueprintData.Visibility visibility,
            @Nullable String description) {
        String apiEndpoint = plugin.getConfig().getString("blueprint.web.apiEndpoint", "");
        if (apiEndpoint.isEmpty()) {
            return CompletableFuture.completedFuture(
                new PublishResult(K + "publish.no_endpoint", null, false, null));
        }

        BlueprintValidator.ValidationResult validation = BlueprintValidator.validate(data);
        if (!validation.valid()) {
            plugin.getLogger().warning("Publish validation failed: " + validation.reason());
            return CompletableFuture.completedFuture(
                new PublishResult(K + "publish.invalid_data", null, false, null));
        }

        URI endpoint = blueprintCollectionUri(apiEndpoint);
        if (endpoint == null) {
            return CompletableFuture.completedFuture(
                new PublishResult(K + "publish.no_endpoint", null, false, null));
        }

        try {
            io.publications().verifyWritable(player.getUniqueId());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not prepare SculptWeb management key storage for "
                + player.getUniqueId() + ": " + rootMessage(e));
            return CompletableFuture.completedFuture(
                new PublishResult(K + "publish.key_store_unavailable", null, false, null));
        }

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("blueprintId", data.blueprintId().toString());
        payload.put("name", data.name());
        payload.put("description", description != null ? description : data.description());
        payload.put("createdTimestamp", data.createdTimestamp());
        payload.put("lastModifiedTimestamp", System.currentTimeMillis());
        payload.put("minecraftVersion", data.minecraftVersion());
        payload.put("blockKey", data.blockKey());
        payload.put("matchedVariantKey", data.matchedVariantKey());
        payload.put("isMixed", data.isMixed());
        payload.put("maxDepth", data.maxDepth());
        payload.put("gridN", data.gridN());
        payload.put("octreeData", java.util.Base64.getEncoder().encodeToString(data.octreeData()));
        payload.put("leafCoordinates", data.leafCoordinates());
        if (data.hasBlockCollection()) {
            payload.put("blocks", data.blocks());
            payload.put("sizeX", data.sizeX());
            payload.put("sizeY", data.sizeY());
            payload.put("sizeZ", data.sizeZ());
        }
        payload.put("referenceFacing", data.referenceFacing());
        payload.put("visibility", visibility.name());
        addSubmitterMetadata(payload, player.getUniqueId(), player.getName());

        HttpRequest request = HttpRequest.newBuilder()
            .uri(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(
                io.gson().toJson(payload), java.nio.charset.StandardCharsets.UTF_8))
            .build();
        UUID playerUuid = player.getUniqueId();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            .thenApply(response -> processPublishResponse(
                response, playerUuid, data, endpoint.toString()))
            .exceptionally(error -> {
                plugin.getLogger().warning("Publish network error for " + data.blueprintId()
                    + ": " + rootMessage(error));
                return new PublishResult(K + "publish.network_error", null, false, null);
            });
    }

    // ====================== 從 SculptWeb 取消發布 ======================

    /** Deletes a player's previously published SculptWeb copy using its stored key. */
    public CompletableFuture<UnpublishResult> unpublishBlueprint(
            UUID playerUuid, String nameOrId) {
        final BlueprintPublicationStore.Publication publication;
        try {
            publication = io.publications().find(playerUuid, nameOrId);
        } catch (IOException e) {
            return CompletableFuture.completedFuture(new UnpublishResult(
                K + "unpublish.local_store_error", null, false));
        }
        if (publication == null) {
            return CompletableFuture.completedFuture(new UnpublishResult(
                K + "unpublish.not_found", nameOrId, false));
        }

        String collection = publication.collectionUri();
        if (collection == null || collection.isBlank()) {
            String configured = plugin.getConfig().getString("blueprint.web.apiEndpoint", "");
            URI configuredCollection = blueprintCollectionUri(configured);
            collection = configuredCollection != null ? configuredCollection.toString() : null;
        }
        URI endpoint = blueprintItemUri(collection, publication.remoteBlueprintId());
        if (endpoint == null) {
            return CompletableFuture.completedFuture(new UnpublishResult(
                K + "unpublish.no_endpoint", publication.name(), false));
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(endpoint)
            .header("Accept", "application/json")
            .header("X-Edit-Token", publication.editToken())
            .timeout(Duration.ofSeconds(15))
            .DELETE()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            .thenApply(response -> processUnpublishResponse(
                response, playerUuid, publication))
            .exceptionally(error -> {
                plugin.getLogger().warning("Unpublish network error for "
                    + publication.remoteBlueprintId() + ": " + rootMessage(error));
                return new UnpublishResult(
                    K + "unpublish.network_error", publication.name(), false);
            });
    }

    public List<String> publishedBlueprintNames(UUID playerUuid) {
        try {
            return io.publications().list(playerUuid).stream()
                .map(BlueprintPublicationStore.Publication::name)
                .distinct()
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Nullable
    private String processDownloadResponse(HttpResponse<InputStream> response, int limit,
                                           UUID playerUuid, String playerName, String host) {
        try (InputStream bodyStream = response.body()) {
            if (response.statusCode() == 410) return K + "download.expired";
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                return K + "download.domain_not_allowed";
            }
            if (response.statusCode() != 200) return K + "download.http_error";
            if (declaredLengthExceeds(response, limit)) return K + "download.too_large";

            String contentType = response.headers().firstValue("Content-Type")
                .orElse("").toLowerCase(java.util.Locale.ROOT);
            if (!contentType.contains("application/json") && !contentType.contains("+json")) {
                return K + "download.format_error";
            }

            byte[] body = readCapped(bodyStream, limit);
            BlueprintData remote;
            try {
                remote = io.gson().fromJson(
                    new String(body, java.nio.charset.StandardCharsets.UTF_8), BlueprintData.class);
            } catch (RuntimeException e) {
                return K + "download.format_error";
            }
            BlueprintValidator.ValidationResult validation = BlueprintValidator.validate(remote);
            if (!validation.valid()) return K + "download.invalid_data";

            int maximum = plugin.getConfig().getInt(
                "blueprint.storage.maxPerPlayer", DEFAULT_MAX_BLUEPRINTS_PER_PLAYER);
            if (maximum > 0 && countBlueprints(playerUuid, false) >= maximum) {
                return K + "save.limit_reached";
            }

            long now = System.currentTimeMillis();
            BlueprintData local = new BlueprintData(
                UUID.randomUUID(), remote.name(), remote.description(), now, now,
                remote.minecraftVersion(), remote.blockKey(), remote.matchedVariantKey(),
                remote.isMixed(), remote.maxDepth(), remote.gridN(), remote.octreeData(),
                remote.leafCoordinates(), remote.blocks(), remote.sizeX(), remote.sizeY(),
                remote.sizeZ(), remote.referenceFacing(),
                BlueprintData.Visibility.PRIVATE, null);
            io.writeBlueprint(playerUuid, local, false);
            io.updatePlayerIndex(playerUuid, playerName);
            plugin.getLogger().info(playerName + " downloaded blueprint " + local.name()
                + " from " + host + " as " + local.blueprintId());
            return null;
        } catch (ResponseTooLargeException e) {
            return K + "download.too_large";
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private PublishResult processPublishResponse(HttpResponse<InputStream> response,
                                                  UUID playerUuid, BlueprintData data,
                                                  String collectionUri) {
        try (InputStream bodyStream = response.body()) {
            if (response.statusCode() == 429) {
                return new PublishResult(K + "publish.rate_limited", null, false, null);
            }
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                return new PublishResult(K + "publish.http_error", null, false, null);
            }
            if (response.statusCode() != 201 || declaredLengthExceeds(response, 65_536)) {
                return new PublishResult(K + "publish.http_error", null, false, null);
            }

            byte[] body = readCapped(bodyStream, 65_536);
            ApiPublishResponse apiResponse;
            try {
                apiResponse = io.gson().fromJson(
                    new String(body, java.nio.charset.StandardCharsets.UTF_8),
                    ApiPublishResponse.class);
            } catch (RuntimeException e) {
                return new PublishResult(K + "publish.invalid_response", null, false, null);
            }
            if (!validPublishResponse(apiResponse)) {
                return new PublishResult(K + "publish.invalid_response", null, false, null);
            }

            UUID remoteId = UUID.fromString(apiResponse.id);
            io.publications().save(playerUuid, new BlueprintPublicationStore.Publication(
                data.blueprintId(), remoteId, data.name(), apiResponse.editToken,
                collectionUri, apiResponse.shareUrl, System.currentTimeMillis()));
            return new PublishResult(
                null, apiResponse.shareUrl, true, apiResponse.accessPassword);
        } catch (ResponseTooLargeException e) {
            return new PublishResult(K + "publish.invalid_response", null, false, null);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not store SculptWeb management key for "
                + data.blueprintId() + ": " + rootMessage(e));
            return new PublishResult(K + "publish.key_store_error", null, false, null);
        }
    }

    private UnpublishResult processUnpublishResponse(
            HttpResponse<InputStream> response, UUID playerUuid,
            BlueprintPublicationStore.Publication publication) {
        try (InputStream ignored = response.body()) {
            int status = response.statusCode();
            if (status == 200 || status == 404 || status == 410) {
                io.publications().remove(playerUuid, publication.localBlueprintId());
                return new UnpublishResult(null, publication.name(), status != 200);
            }
            if (status == 401 || status == 403) {
                return new UnpublishResult(
                    K + "unpublish.invalid_key", publication.name(), false);
            }
            if (status == 429) {
                return new UnpublishResult(
                    K + "unpublish.rate_limited", publication.name(), false);
            }
            return new UnpublishResult(
                K + "unpublish.http_error", publication.name(), false);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static boolean validPublishResponse(@Nullable ApiPublishResponse response) {
        if (response == null || response.id == null || response.id.length() > 128) return false;
        try {
            UUID.fromString(response.id);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (response.editToken == null || response.editToken.isBlank()
                || response.editToken.length() > 512
                || response.editToken.indexOf('\r') >= 0
                || response.editToken.indexOf('\n') >= 0) return false;
        if (response.accessPassword != null && response.accessPassword.length() > 512) return false;
        if (response.shareUrl != null) {
            if (response.shareUrl.length() > 2_048) return false;
            try {
                if (normalizedHttpsHost(URI.create(response.shareUrl)) == null) return false;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    static URI blueprintCollectionUri(@Nullable String apiEndpoint) {
        if (apiEndpoint == null || apiEndpoint.isBlank()) return null;
        try {
            URI base = URI.create(apiEndpoint);
            String host = normalizedHttpsHost(base);
            if (host == null) return null;
            String basePath = base.getPath() == null
                ? "" : base.getPath().replaceAll("/+$", "");
            return new URI("https", null, host, -1,
                basePath + "/blueprints", null, null);
        } catch (IllegalArgumentException | URISyntaxException e) {
            return null;
        }
    }

    @Nullable
    static URI blueprintItemUri(@Nullable String collectionUri, UUID blueprintId) {
        if (collectionUri == null || collectionUri.isBlank()) return null;
        try {
            URI collection = URI.create(collectionUri);
            String host = normalizedHttpsHost(collection);
            if (host == null || collection.getRawQuery() != null
                    || collection.getRawFragment() != null) return null;
            String path = collection.getPath() == null
                ? "" : collection.getPath().replaceAll("/+$", "");
            return new URI("https", null, host, -1,
                path + "/" + blueprintId, null, null);
        } catch (IllegalArgumentException | URISyntaxException e) {
            return null;
        }
    }

    static byte[] readCapped(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 16_384));
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximum) throw new ResponseTooLargeException();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean declaredLengthExceeds(HttpResponse<?> response, int maximum) {
        return response.headers().firstValueAsLong("Content-Length")
            .stream().anyMatch(length -> length > maximum);
    }

    @Nullable
    static String normalizedHttpsHost(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getHost() == null) {
            return null;
        }
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    @Nullable
    static String queryParameter(URI uri, String requestedName) {
        String query = uri.getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8);
            if (requestedName.equals(name)) {
                return parts.length == 2
                    ? URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8) : "";
            }
        }
        return null;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    private static final class ResponseTooLargeException extends IOException {}

    /** 發布結果。error 為 null 代表成功。 */
    public record PublishResult(
        @Nullable String error,
        @Nullable String shareUrl,
        boolean managementKeyStored,
        @Nullable String accessPassword
    ) {}

    /** Remote deletion result. {@code error == null} means the remote copy is absent. */
    public record UnpublishResult(
        @Nullable String error,
        @Nullable String name,
        boolean alreadyAbsent
    ) {}

    @SuppressWarnings("unused")
    private static class ApiPublishResponse {
        String id;
        String shareUrl;
        String editToken;
        String accessPassword;
    }

    // ====================== 藍圖物品 ======================

    /**
     * 建立藍圖物品（PAPER + PDC）。
     */
    public ItemStack createBlueprintItem(UUID blueprintId) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, "blueprint_id"),
                PersistentDataType.STRING, blueprintId.toString());
            meta.setDisplayName("§6藍圖");
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 建立藍圖選取工具（BLAZE_ROD + PDC）。
     */
    public ItemStack createSelectorItem() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("sculpt", "blueprint_selector"),
                PersistentDataType.STRING, "true");
            meta.setDisplayName("§6藍圖選取工具");
            meta.setLore(java.util.List.of(
                "§7左鍵選取 / 右鍵貼上",
                "§7快速按兩下 F: 切換單顆 / 長方體模式",
                "§7單按 F: 取消選取"));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 檢查物品是否為藍圖物品。
     */
    public boolean isBlueprintItem(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(
            new org.bukkit.NamespacedKey(plugin, "blueprint_id"),
            PersistentDataType.STRING);
    }

    /**
     * 從物品讀取藍圖 ID。
     */
    @Nullable
    public UUID getBlueprintIdFromItem(ItemStack item) {
        if (!isBlueprintItem(item)) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(
            new org.bukkit.NamespacedKey(plugin, "blueprint_id"),
            PersistentDataType.STRING);
        if (id == null) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 從物品讀取貼上設定（PDC）。
     */
    public PasteSettings getPasteSettingsFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return PasteSettings.DEFAULTS;
        var pdc = item.getItemMeta().getPersistentDataContainer();

        // 讀取各項設定（若存在）
        Boolean pasteAir = getPdcBoolean(pdc, PDC_PASTE_AIR);
        Boolean overwrite = getPdcBoolean(pdc, PDC_OVERWRITE);
        Boolean adhesive = getPdcBoolean(pdc, PDC_ADHESIVE);
        String rotateMode = getPdcString(pdc, PDC_ROTATE_MODE);
        Integer ry = getPdcInt(pdc, PDC_RY);
        String flip = getPdcString(pdc, PDC_FLIP);

        return PasteSettings.fromCommand(pasteAir, overwrite, adhesive, rotateMode, ry, flip);
    }

    // ====================== 選取 ======================

    public boolean hasSelection(Player player) {
        UUID playerId = player.getUniqueId();
        return selectedBlocks.containsKey(playerId) || selectedRegions.containsKey(playerId);
    }

    public SelectionMode getSelectionMode(Player player) {
        return selectionModes.getOrDefault(player.getUniqueId(), SelectionMode.SINGLE);
    }

    public SelectionMode toggleSelectionMode(Player player) {
        UUID playerId = player.getUniqueId();
        SelectionMode current = getSelectionMode(player);
        SelectionMode next = current == SelectionMode.SINGLE
            ? SelectionMode.CUBOID : SelectionMode.SINGLE;
        selectionModes.put(playerId, next);
        regionFirstCorners.remove(playerId);
        if (next == SelectionMode.CUBOID && !selectedRegions.containsKey(playerId)) {
            selectedBlocks.remove(playerId);
            selectedReferenceFacings.remove(playerId);
            removeSelectionHighlight(player);
        }
        return next;
    }

    @Nullable
    public String getSelectedReferenceFacing(Player player) {
        return selectedReferenceFacings.get(player.getUniqueId());
    }

    public void setSelectedBlock(Player player, @Nullable SculptBlock block) {
        UUID playerId = player.getUniqueId();
        if (block != null) {
            selectedBlocks.put(playerId, block);
            selectedRegions.remove(playerId);
            regionFirstCorners.remove(playerId);
            Location center = new Location(
                block.world, block.centerX, block.centerY, block.centerZ);
            selectedReferenceFacings.put(playerId,
                PasteEngine.facingFromPlayerPosition(player, center).name());
            showSelectionHighlight(player, block);
        } else {
            removeSelectionHighlight(player);
            selectedBlocks.remove(playerId);
            selectedReferenceFacings.remove(playerId);
        }
    }

    public SelectionResult selectFirstCorner(Player player, Location location,
                                             @Nullable SculptBlock sculptBlock) {
        if (getSelectionMode(player) == SelectionMode.SINGLE) {
            if (sculptBlock == null) return SelectionResult.NOT_SCULPT_BLOCK;
            setSelectedBlock(player, sculptBlock);
            return SelectionResult.SINGLE_SELECTED;
        }

        UUID playerId = player.getUniqueId();
        Location corner = blockLocation(location);
        regionFirstCorners.put(playerId, corner);
        selectedBlocks.remove(playerId);
        selectedRegions.remove(playerId);
        selectedReferenceFacings.remove(playerId);
        showSelectionHighlight(player, SelectionBounds.single(corner));
        return SelectionResult.FIRST_CORNER_SELECTED;
    }

    public SelectionResult selectSecondCorner(Player player, Location location) {
        if (getSelectionMode(player) != SelectionMode.CUBOID) {
            return SelectionResult.NOT_CUBOID_MODE;
        }
        UUID playerId = player.getUniqueId();
        Location first = regionFirstCorners.get(playerId);
        if (first == null) return SelectionResult.FIRST_CORNER_REQUIRED;
        if (first.getWorld() == null || location.getWorld() == null
                || !first.getWorld().getUID().equals(location.getWorld().getUID())) {
            return SelectionResult.DIFFERENT_WORLD;
        }

        SelectionBounds bounds = SelectionBounds.between(first, blockLocation(location));
        int configuredLimit = plugin.getConfig().getInt(
            "blueprint.selection.maxVolume", BlueprintValidator.MAX_SELECTION_VOLUME);
        int maximum = Math.max(1, Math.min(
            configuredLimit, BlueprintValidator.MAX_SELECTION_VOLUME));
        if (bounds.volume() > maximum) return SelectionResult.SELECTION_TOO_LARGE;

        selectedRegions.put(playerId, bounds);
        selectedBlocks.remove(playerId);
        regionFirstCorners.remove(playerId);
        Location center = bounds.center();
        selectedReferenceFacings.put(playerId,
            PasteEngine.facingFromPlayerPosition(player, center).name());
        showSelectionHighlight(player, bounds);
        return SelectionResult.CUBOID_SELECTED;
    }

    public void clearSelection(Player player) {
        removeSelectionHighlight(player);
        UUID playerId = player.getUniqueId();
        selectedBlocks.remove(playerId);
        selectedRegions.remove(playerId);
        regionFirstCorners.remove(playerId);
        selectedReferenceFacings.remove(playerId);
    }

    /**
     * Validate that a synchronous selection snapshot stays on the current
     * Folia region. This check must run before reading any selected block.
     */
    @Nullable
    public String selectionAccessError(Player player) {
        UUID playerId = player.getUniqueId();
        SculptBlock selected = selectedBlocks.get(playerId);
        if (selected != null && !FoliaRegionGuard.owns(selected.pos)) {
            return K + "folia_cross_region";
        }

        SelectionBounds region = selectedRegions.get(playerId);
        if (region != null && !FoliaRegionGuard.ownsCuboid(
                region.world(), region.minX(), region.minZ(),
                region.maxX(), region.maxZ())) {
            return K + "folia_cross_region";
        }
        return null;
    }

    /** Build an in-memory snapshot for direct selector pastes and persistent saves. */
    @Nullable
    public BlueprintData createBlueprintFromSelection(
            Player player, String name, BlueprintData.Visibility visibility) {
        // Future callers must remain safe even if they forget to preflight the
        // selection. Public interaction paths check first to retain the error key.
        if (selectionAccessError(player) != null) return null;
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        SculptBlock selected = selectedBlocks.get(playerId);
        if (selected != null) {
            return createSingleBlockBlueprint(
                selected, UUID.randomUUID(), name, now,
                getSelectedReferenceFacing(player), visibility);
        }

        SelectionBounds region = selectedRegions.get(playerId);
        if (region == null) return null;
        List<BlueprintBlockData> blocks = new ArrayList<>();
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int y = region.minY(); y <= region.maxY(); y++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    int relativeX = x - region.minX();
                    int relativeY = y - region.minY();
                    int relativeZ = z - region.minZ();
                    Block block = region.world().getBlockAt(x, y, z);
                    SculptBlock sculpt = plugin.getActiveBlock(
                        new BlockPosKey(region.world().getName(), x, y, z));
                    if (sculpt != null) {
                        blocks.add(createBlockData(
                            sculpt, relativeX, relativeY, relativeZ));
                        continue;
                    }
                    if (!block.getType().isAir()) {
                        blocks.add(createRegularBlockData(
                            block, relativeX, relativeY, relativeZ));
                    }
                }
            }
        }
        if (blocks.isEmpty()) return null;

        BlueprintBlockData representative = blocks.stream()
            .filter(BlueprintBlockData::isSculptBlock)
            .findFirst()
            .orElseGet(() -> compatibilityRepresentative(blocks.get(0)));
        String commonBlockKey = representative.blockKey();
        String commonVariant = representative.matchedVariantKey();
        boolean mixed = representative.isMixed();
        for (BlueprintBlockData block : blocks) {
            if (!Objects.equals(commonBlockKey, block.blockKey())) commonBlockKey = null;
            if (!Objects.equals(commonVariant, block.matchedVariantKey())) commonVariant = null;
            mixed |= block.isMixed();
        }
        mixed |= commonBlockKey == null;

        return new BlueprintData(
            UUID.randomUUID(), name, null, now, now,
            plugin.getServer().getMinecraftVersion(), commonBlockKey, commonVariant, mixed,
            representative.maxDepth(), representative.gridN(), representative.octreeData(),
            representative.leafCoordinates(), blocks,
            region.sizeX(), region.sizeY(), region.sizeZ(),
            getSelectedReferenceFacing(player), visibility, null);
    }

    private BlueprintData createSingleBlockBlueprint(
            SculptBlock block, UUID blueprintId, String name, long timestamp,
            @Nullable String referenceFacing, BlueprintData.Visibility visibility) {
        BlueprintBlockData snapshot = createBlockData(block, 0, 0, 0);
        return new BlueprintData(
            blueprintId, name, null, timestamp, timestamp,
            plugin.getServer().getMinecraftVersion(), snapshot.blockKey(),
            snapshot.matchedVariantKey(), snapshot.isMixed(), snapshot.maxDepth(),
            snapshot.gridN(), snapshot.octreeData(), snapshot.leafCoordinates(),
            referenceFacing, visibility, null);
    }

    private static BlueprintBlockData createBlockData(
            SculptBlock block, int x, int y, int z) {
        String blockKey = block.originalBlockData != null
            ? block.originalBlockData.getMaterial().getKey().toString() : null;
        return new BlueprintBlockData(
            x, y, z, blockKey, block.matchedVariantKey, block.isMixed(), block.maxDepth,
            1 << block.maxDepth, block.root.serialize(), collectLeafCoordinates(block),
            BlueprintBlockData.Kind.SCULPT, null);
    }

    private static BlueprintBlockData createRegularBlockData(
            Block block, int x, int y, int z) {
        org.bukkit.block.data.BlockData data = block.getBlockData();
        return new BlueprintBlockData(
            x, y, z, data.getMaterial().getKey().toString(), null, false,
            0, 1, null, null, BlueprintBlockData.Kind.BLOCK, data.getAsString());
    }

    /** Keep the legacy top-level snapshot valid for readers that do not know collections. */
    private static BlueprintBlockData compatibilityRepresentative(BlueprintBlockData block) {
        OctreeNode root = new OctreeNode();
        root.setBlockData(org.bukkit.Bukkit.createBlockData(block.blockData()));
        return new BlueprintBlockData(
            0, 0, 0, block.blockKey(), null, false, 0, 1,
            root.serialize(), null, BlueprintBlockData.Kind.SCULPT, null);
    }

    // ====================== 選取高亮 ======================

    /**
     * 在選取的 SculptBlock 位置顯示淺藍色染色玻璃高亮。
     * 使用單一 ItemDisplay，尺寸調整為方塊大小 + 微小間距。
     */
    private void showSelectionHighlight(Player player, SculptBlock block) {
        showSelectionHighlight(player, SelectionBounds.single(block.pos));
    }

    private void showSelectionHighlight(Player player, SelectionBounds bounds) {
        removeSelectionHighlight(player);

        Location center = bounds.center();

        PrivateItemDisplay display = new PrivateItemDisplay(plugin);
        selectionDisplays.put(player.getUniqueId(), display);
        display.show(center, player, d -> {
            d.setItemStack(new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS));
            d.setTransformation(new Transformation(
                new Vector3f(), new Quaternionf(),
                new Vector3f(
                    bounds.sizeX() + 0.05f,
                    bounds.sizeY() + 0.05f,
                    bounds.sizeZ() + 0.05f),
                new Quaternionf()));
            d.setDisplayWidth(0);
            d.setDisplayHeight(0);
            d.setViewRange(100);
            d.setVisibleByDefault(false);
            d.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("sculpt", "hover"),
                PersistentDataType.STRING, "glass");
        });

    }

    /**
     * 移除玩家的選取高亮顯示。
     */
    private void removeSelectionHighlight(Player player) {
        PrivateItemDisplay display = selectionDisplays.remove(player.getUniqueId());
        if (display != null) {
            display.clear();
        }
    }

    private static Location blockLocation(Location location) {
        return new Location(location.getWorld(), location.getBlockX(),
            location.getBlockY(), location.getBlockZ());
    }

    public enum SelectionMode { SINGLE, CUBOID }

    public enum SelectionResult {
        SINGLE_SELECTED,
        FIRST_CORNER_SELECTED,
        CUBOID_SELECTED,
        NOT_SCULPT_BLOCK,
        NOT_CUBOID_MODE,
        FIRST_CORNER_REQUIRED,
        DIFFERENT_WORLD,
        SELECTION_TOO_LARGE
    }

    private record SelectionBounds(
            World world, int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
        private static SelectionBounds single(Location location) {
            return between(location, location);
        }

        private static SelectionBounds between(Location first, Location second) {
            World world = Objects.requireNonNull(first.getWorld());
            return new SelectionBounds(
                world,
                Math.min(first.getBlockX(), second.getBlockX()),
                Math.min(first.getBlockY(), second.getBlockY()),
                Math.min(first.getBlockZ(), second.getBlockZ()),
                Math.max(first.getBlockX(), second.getBlockX()),
                Math.max(first.getBlockY(), second.getBlockY()),
                Math.max(first.getBlockZ(), second.getBlockZ()));
        }

        private int sizeX() { return maxX - minX + 1; }
        private int sizeY() { return maxY - minY + 1; }
        private int sizeZ() { return maxZ - minZ + 1; }
        private long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

        private Location center() {
            return new Location(world,
                minX + sizeX() / 2.0,
                minY + sizeY() / 2.0,
                minZ + sizeZ() / 2.0);
        }
    }

    // ====================== 公共查詢 ======================

    public BlueprintIO io() { return io; }
    public FolderManager folderManager() { return folderManager; }
    public PasteEngine pasteEngine() { return pasteEngine; }

    // ====================== 匯出/匯入 ======================

    /** 匯出目錄路徑：plugins/Sculpt/export/ */
    private Path exportDir() {
        return plugin.getDataFolder().toPath().resolve("export");
    }

    /**
     * 匯出藍圖到獨立檔案。
     *
     * @param player 玩家
     * @param data   藍圖資料
     * @return 錯誤訊息（null = 成功）
     */
    @Nullable
    public String exportBlueprint(Player player, BlueprintData data) {
        try {
            Path dir = exportDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(sanitizeFilename(data.name()) + ".blueprint");
            // Always re-serialize so legacy local-only credentials cannot enter exports.
            io.writeBlueprintTo(target, data);
            return null;
        } catch (IOException e) {
            return K + "export.failed";
        }
    }

    /**
     * 從匯出目錄匯入藍圖。
     *
     * @param player 玩家
     * @param filename 檔案名稱（不含路徑）
     * @return 錯誤訊息（null = 成功）
     */
    @Nullable
    public String importBlueprint(Player player, String filename) {
        try {
            Path dir = exportDir().toAbsolutePath().normalize();
            Path file = dir.resolve(filename).normalize();
            if (!file.startsWith(dir) || Path.of(filename).getNameCount() != 1) {
                return K + "import.not_found";
            }
            if (!Files.exists(file) || !file.getFileName().toString().endsWith(".blueprint")) {
                return K + "import.not_found";
            }
            BlueprintData data = io.readBlueprintFrom(file);
            if (data == null) {
                return K + "import.invalid_data";
            }
            UUID playerUuid = player.getUniqueId();
            int maximum = plugin.getConfig().getInt(
                "blueprint.storage.maxPerPlayer", DEFAULT_MAX_BLUEPRINTS_PER_PLAYER);
            if (maximum > 0 && countBlueprints(playerUuid, false) >= maximum) {
                return K + "save.limit_reached";
            }
            UUID localId = Files.exists(io.blueprintFilePath(playerUuid, data.blueprintId(), false))
                ? UUID.randomUUID() : data.blueprintId();
            BlueprintData local = new BlueprintData(
                localId, data.name(), data.description(), data.createdTimestamp(),
                System.currentTimeMillis(), data.minecraftVersion(), data.blockKey(),
                data.matchedVariantKey(), data.isMixed(), data.maxDepth(), data.gridN(),
                data.octreeData(), data.leafCoordinates(), data.blocks(), data.sizeX(),
                data.sizeY(), data.sizeZ(), data.referenceFacing(),
                BlueprintData.Visibility.PRIVATE, null);
            io.writeBlueprint(playerUuid, local, false);
            io.updatePlayerIndex(playerUuid, player.getName());
            return null;
        } catch (IOException | RuntimeException e) {
            return K + "import.failed";
        }
    }

    /** 列出匯出目錄中的 .blueprint 檔案。 */
    public List<String> listExportFiles() {
        try {
            Path dir = exportDir();
            if (!Files.exists(dir)) return List.of();
            List<String> result = new ArrayList<>();
            try (var stream = Files.newDirectoryStream(dir, "*.blueprint")) {
                for (Path f : stream) result.add(f.getFileName().toString());
            }
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replace(" ", "_");
    }

    // ====================== Per-Player 設定 ======================

    /**
     * 讀取玩家的貼上設定。
     *
     * @param playerUuid 玩家 UUID
     * @return PasteSettings，預設為 DEFAULTS
     */
    public PasteSettings getPlayerSettings(UUID playerUuid) {
        try {
            if (!Files.exists(io.settingsFilePath(playerUuid))) return defaultPasteSettings();
            return io.readPlayerSettings(playerUuid);
        } catch (IOException | RuntimeException e) {
            return defaultPasteSettings();
        }
    }

    /**
     * 寫入玩家的貼上設定。
     *
     * @param playerUuid 玩家 UUID
     * @param settings   新的貼上設定
     */
    public void setPlayerSettings(UUID playerUuid, PasteSettings settings) {
        try {
            io.writePlayerSettings(playerUuid, settings);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write player settings for " + playerUuid + ": " + e.getMessage());
        }
    }

    // ====================== 內部方法 ======================

    private int countBlueprints(UUID playerUuid, boolean isPublic) throws IOException {
        Path dir = (isPublic ? io.publicPlayerDir(playerUuid) : io.privatePlayerDir(playerUuid))
            .resolve("blueprints");
        if (!Files.exists(dir)) return 0;
        int count = 0;
        try (var stream = Files.newDirectoryStream(dir, "*.blueprint")) {
            for (@SuppressWarnings("unused") var f : stream) count++;
        }
        return count;
    }

    private void updatePublicIndexAdd(BlueprintData data, UUID submitterUuid,
                                      String submitterName) throws IOException {
        BlueprintIO.PublicIndex index = io.readPublicIndex();
        if (index.blueprints == null) index.blueprints = new ArrayList<>();

        BlueprintIO.PublicIndex.PublicEntry entry = new BlueprintIO.PublicIndex.PublicEntry();
        entry.blueprintId = data.blueprintId().toString();
        entry.name = data.name();
        entry.submitterUUID = submitterUuid.toString();
        entry.submitterName = submitterName;
        entry.gridN = data.gridN();
        entry.blockKey = data.blockKey();
        entry.createdTimestamp = data.createdTimestamp();
        entry.cellCount = 0; // 可選，由呼叫者填入
        index.blueprints.add(entry);
        index.lastUpdated = System.currentTimeMillis();
        io.writePublicIndex(index);
    }

    private void updatePublicIndexRemove(UUID blueprintId) throws IOException {
        BlueprintIO.PublicIndex index = io.readPublicIndex();
        if (index.blueprints == null) return;
        index.blueprints.removeIf(e -> e.blueprintId.equals(blueprintId.toString()));
        index.lastUpdated = System.currentTimeMillis();
        io.writePublicIndex(index);
    }

    static UUID extractBlueprintId(String path) {
        // 支援 /blueprint/<uuid> 或 /api/blueprints/<uuid>/download
        if (path == null) return null;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ((parts[i].equals("blueprint") || parts[i].equals("blueprints"))
                    && i + 1 < parts.length) {
                try {
                    return UUID.fromString(parts[i + 1]);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    static void addSubmitterMetadata(Map<String, Object> payload,
                                     UUID submitterUuid, String submitterName) {
        payload.put("submitterName", submitterName);
        payload.put("submitterUUID", submitterUuid.toString());
    }

    private boolean isAllowedDownloadDomain(String host) {
        if (host == null) return false;
        var allowedDomains = plugin.getConfig().getStringList("blueprint.download.allowedDomains");
        for (String domain : allowedDomains) {
            String normalized = domain.trim().toLowerCase(java.util.Locale.ROOT);
            if (host.equals(normalized)) return true;
        }
        return false;
    }

    private Boolean getPdcBoolean(org.bukkit.persistence.PersistentDataContainer pdc, String key) {
        var nsk = new org.bukkit.NamespacedKey(plugin, key.replace("sculpt:", ""));
        if (pdc.has(nsk, PersistentDataType.BOOLEAN)) {
            return pdc.get(nsk, PersistentDataType.BOOLEAN);
        }
        if (pdc.has(nsk, PersistentDataType.STRING)) {
            return Boolean.parseBoolean(pdc.get(nsk, PersistentDataType.STRING));
        }
        return null;
    }

    private String getPdcString(org.bukkit.persistence.PersistentDataContainer pdc, String key) {
        var nsk = new org.bukkit.NamespacedKey(plugin, key.replace("sculpt:", ""));
        if (pdc.has(nsk, PersistentDataType.STRING)) {
            return pdc.get(nsk, PersistentDataType.STRING);
        }
        return null;
    }

    private Integer getPdcInt(org.bukkit.persistence.PersistentDataContainer pdc, String key) {
        var nsk = new org.bukkit.NamespacedKey(plugin, key.replace("sculpt:", ""));
        if (pdc.has(nsk, PersistentDataType.INTEGER)) {
            return pdc.get(nsk, PersistentDataType.INTEGER);
        }
        return null;
    }

    // ====================== Cell 座標收集 ======================

    /**
     * 收集 SculptBlock 每片葉子的規範化座標（已套用 blockRotation）。
     * <p>
     * 這些座標是經過 {@link HeadResolver#rotateCoord} 映射後的規範化座標，
     * 貼上時可直接用於 HeadsRegistry 查詢，不受還原時方塊旋轉的影響。
     *
     * @param block SculptBlock
     * @return 路徑字串 → [x, y, z] 的映射（規範化座標），若無葉子則為 empty map
     */
    private static java.util.Map<String, int[]> collectLeafCoordinates(SculptBlock block) {
        java.util.Map<String, int[]> result = new java.util.HashMap<>();
        List<OctreeNode> leaves = new ArrayList<>();
        block.root.collectAllLeaves(leaves);
        int gridN = 1 << block.maxDepth;
        for (OctreeNode leaf : leaves) {
            int side = leaf.side();
            int gx = leaf.minX() / side;
            int gy = leaf.minY() / side;
            int gz = leaf.minZ() / side;
            ChunkCoord physical = new ChunkCoord(gx, gy, gz);
            ChunkCoord canonical = HeadResolver.rotateCoord(physical, block.blockRotation, gridN);
            result.put(leaf.pathAsString(), new int[]{canonical.x(), canonical.y(), canonical.z()});
        }
        return result;
    }

    // ====================== 公開記錄 ======================

    /** 藍圖摘要（用於 list 顯示）。 */
    public record BlueprintSummary(
        UUID blueprintId,
        String name,
        @Nullable String blockKey,
        int gridN,
        long createdTimestamp,
        @Nullable BlueprintData.Visibility visibility
    ) {}

    /** 公開藍圖條目。 */
    public record PublicBlueprintEntry(
        UUID blueprintId,
        String name,
        @Nullable UUID submitterUuid,
        @Nullable String submitterName,
        int gridN,
        @Nullable String blockKey,
        long createdTimestamp
    ) {}
}
