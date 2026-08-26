package dev.twme.sculpt.blueprint;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.SerializedName;

/**
 * BlueprintIO — 負責 .blueprint 檔案的讀寫與序列化。
 * <p>
 * 處理 plugins/Sculpt/blueprints/ 目錄下的藍圖檔案，包含：
 * - 寫入/讀取 .blueprint JSON 檔案
 * - 管理目錄結構（private / public / downloads）
 * - player-index.json 的讀寫
 */
public class BlueprintIO {

    private final Path blueprintsDir;
    private final Gson gson;
    private final BlueprintPublicationStore publicationStore;

    public BlueprintIO(Path blueprintsDir) {
        this.blueprintsDir = blueprintsDir;

        // 自訂 Gson：byte[] ↔ Base64
        this.gson = new GsonBuilder()
            .registerTypeAdapter(byte[].class, (JsonSerializer<byte[]>) (src, type, ctx) ->
                new JsonPrimitive(Base64.getEncoder().encodeToString(src)))
            .registerTypeAdapter(byte[].class, (JsonDeserializer<byte[]>) (src, type, ctx) ->
                Base64.getDecoder().decode(src.getAsString()))
            .registerTypeAdapter(UUID.class, (JsonSerializer<UUID>) (src, type, ctx) ->
                new JsonPrimitive(src.toString()))
            .registerTypeAdapter(UUID.class, (JsonDeserializer<UUID>) (src, type, ctx) ->
                UUID.fromString(src.getAsString()))
            .setPrettyPrinting()
            .create();
        this.publicationStore = new BlueprintPublicationStore(blueprintsDir, gson);
    }

    /** 公開 Gson 實例（供 BlueprintManager 等使用）。 */
    public Gson gson() { return gson; }

    // ====================== 目錄路徑 ======================

    /** 取得私人的玩家藍圖目錄。 */
    public Path privatePlayerDir(UUID playerUuid) {
        return blueprintsDir.resolve("private").resolve(playerUuid.toString());
    }

    /** 取得公開的玩家藍圖目錄。 */
    public Path publicPlayerDir(UUID playerUuid) {
        return blueprintsDir.resolve("public").resolve(playerUuid.toString());
    }

    /** 取得 downloads 目錄（私人目錄下）。 */
    public Path downloadsDir(UUID playerUuid) {
        return privatePlayerDir(playerUuid).resolve("downloads");
    }

    /** 取得該玩家 blueprints 子目錄。 */
    public Path blueprintsDirFor(UUID playerUuid, boolean isPublic) {
        return (isPublic ? publicPlayerDir(playerUuid) : privatePlayerDir(playerUuid))
            .resolve("blueprints");
    }

    /** 取得 .blueprint 檔案路徑。 */
    public Path blueprintFilePath(UUID playerUuid, UUID blueprintId, boolean isPublic) {
        return blueprintsDirFor(playerUuid, isPublic).resolve(blueprintId + ".blueprint");
    }

    /** 取得公開索引檔案路徑。 */
    public Path publicIndexPath() {
        return blueprintsDir.resolve("public").resolve("public-index.json");
    }

    /** 取得玩家索引檔案路徑。 */
    public Path playerIndexPath() {
        return blueprintsDir.resolve("player-index.json");
    }

    // ====================== 藍圖讀寫 ======================

    /**
     * 將 BlueprintData 寫入 .blueprint 檔案。
     */
    public void writeBlueprint(UUID playerUuid, BlueprintData data, boolean isPublic) throws IOException {
        requireValid(data);
        Path file = blueprintFilePath(playerUuid, data.blueprintId(), isPublic);
        writeJsonAtomically(file, withoutEditToken(data));
    }

    /**
     * 將 BlueprintData 寫入指定路徑（供匯出使用）。
     */
    public void writeBlueprintTo(Path target, BlueprintData data) throws IOException {
        requireValid(data);
        writeJsonAtomically(target, withoutEditToken(data));
    }

    /**
     * 讀取 .blueprint 檔案。
     */
    public BlueprintData readBlueprint(UUID playerUuid, UUID blueprintId, boolean isPublic) throws IOException {
        Path file = blueprintFilePath(playerUuid, blueprintId, isPublic);
        BlueprintData data = readValidatedBlueprint(file);
        if (data == null || data.editToken() == null) return data;

        publicationStore.saveIfAbsent(playerUuid, new BlueprintPublicationStore.Publication(
            data.blueprintId(), data.blueprintId(), data.name(), data.editToken(),
            null, null, data.lastModifiedTimestamp()));
        BlueprintData sanitized = withoutPublicationMetadata(data, isPublic);
        writeJsonAtomically(file, sanitized);
        return sanitized;
    }

    /**
     * 從任意路徑讀取 .blueprint 檔案（用於下載/導入）。
     */
    public BlueprintData readBlueprintFrom(Path file) throws IOException {
        return readValidatedBlueprint(file);
    }

    /**
     * 刪除 .blueprint 檔案。
     */
    public boolean deleteBlueprint(UUID playerUuid, UUID blueprintId, boolean isPublic) throws IOException {
        Path file = blueprintFilePath(playerUuid, blueprintId, isPublic);
        return Files.deleteIfExists(file);
    }

    /**
     * 重新命名藍圖。
     *
     * @param playerUuid 玩家 UUID
     * @param blueprintId 藍圖 ID
     * @param newName     新名稱
     * @param isPublic    是否為公開藍圖
     * @throws IOException 讀寫失敗
     */
    public void renameBlueprint(UUID playerUuid, UUID blueprintId, String newName, boolean isPublic) throws IOException {
        BlueprintData data = readBlueprint(playerUuid, blueprintId, isPublic);
        if (data == null) throw new IOException("Blueprint not found");

        // 建立新的 BlueprintData（僅更新名稱與時間戳）
        BlueprintData renamed = new BlueprintData(
            data.blueprintId(),
            newName,
            data.description(),
            data.createdTimestamp(),
            System.currentTimeMillis(),
            data.minecraftVersion(),
            data.blockKey(),
            data.matchedVariantKey(),
            data.isMixed(),
            data.maxDepth(),
            data.gridN(),
            data.octreeData(),
            data.leafCoordinates(),
            data.blocks(),
            data.sizeX(),
            data.sizeY(),
            data.sizeZ(),
            data.referenceFacing(),
            data.visibility(),
            null
        );

        writeBlueprint(playerUuid, renamed, isPublic);
    }

    BlueprintPublicationStore publications() {
        return publicationStore;
    }

    static BlueprintData withoutEditToken(BlueprintData data) {
        if (data.editToken() == null) return data;
        return copyWithoutEditToken(data, data.visibility());
    }

    private static BlueprintData withoutPublicationMetadata(
            BlueprintData data, boolean isPublic) {
        return copyWithoutEditToken(data, isPublic
            ? BlueprintData.Visibility.PUBLIC : BlueprintData.Visibility.PRIVATE);
    }

    private static BlueprintData copyWithoutEditToken(
            BlueprintData data, BlueprintData.Visibility visibility) {
        return new BlueprintData(
            data.blueprintId(), data.name(), data.description(),
            data.createdTimestamp(), data.lastModifiedTimestamp(),
            data.minecraftVersion(), data.blockKey(), data.matchedVariantKey(),
            data.isMixed(), data.maxDepth(), data.gridN(), data.octreeData(),
            data.leafCoordinates(), data.blocks(), data.sizeX(), data.sizeY(),
            data.sizeZ(), data.referenceFacing(), visibility, null);
    }

    private BlueprintData readValidatedBlueprint(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        if (!Files.isRegularFile(file) || Files.size(file) > BlueprintValidator.MAX_FILE_BYTES) {
            throw new IOException("Blueprint file exceeds the allowed size");
        }
        BlueprintData data;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            data = gson.fromJson(reader, BlueprintData.class);
        } catch (RuntimeException e) {
            throw new IOException("Malformed blueprint JSON", e);
        }
        BlueprintValidator.ValidationResult validation = BlueprintValidator.validate(data);
        if (!validation.valid()) {
            throw new IOException("Invalid blueprint: " + validation.reason());
        }
        return data;
    }

    private void requireValid(BlueprintData data) throws IOException {
        BlueprintValidator.ValidationResult validation = BlueprintValidator.validate(data);
        if (!validation.valid()) {
            throw new IOException("Invalid blueprint: " + validation.reason());
        }
    }

    private void writeJsonAtomically(Path target, Object value) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Target has no parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                gson.toJson(value, writer);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // ====================== Per-Player 設定 ======================

    /** 取得該玩家的設定檔路徑（private/<uuid>/settings.json）。 */
    public Path settingsFilePath(UUID playerUuid) {
        return privatePlayerDir(playerUuid).resolve("settings.json");
    }

    /** 讀取玩家的貼上設定。若設定檔不存在則回傳預設值。 */
    public PasteSettings readPlayerSettings(UUID playerUuid) throws IOException {
        Path file = settingsFilePath(playerUuid);
        if (!Files.exists(file)) return PasteSettings.DEFAULTS;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            PlayerSettingsWrapper wrapper = gson.fromJson(reader, PlayerSettingsWrapper.class);
            if (wrapper != null && wrapper.pasteSettings != null) return wrapper.pasteSettings;
        }
        return PasteSettings.DEFAULTS;
    }

    /** 寫入玩家的貼上設定。 */
    public void writePlayerSettings(UUID playerUuid, PasteSettings settings) throws IOException {
        Path file = settingsFilePath(playerUuid);
        PlayerSettingsWrapper wrapper = new PlayerSettingsWrapper();
        wrapper.pasteSettings = settings;
        writeJsonAtomically(file, wrapper);
    }

    @SuppressWarnings("unused")
    private static class PlayerSettingsWrapper {
        int version = 1;
        PasteSettings pasteSettings;
    }

    // ====================== player-index.json ======================

    /**
     * 註冊或更新玩家在 player-index.json 中的名稱。
     */
    public synchronized void updatePlayerIndex(UUID playerUuid, String playerName) throws IOException {
        Path indexFile = playerIndexPath();
        PlayerIndex index;
        if (Files.exists(indexFile)) {
            try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
                index = gson.fromJson(reader, PlayerIndex.class);
            }
        } else {
            index = new PlayerIndex();
        }

        // 更新或新增
        boolean found = false;
        if (index.players != null) {
            for (PlayerIndex.PlayerEntry entry : index.players) {
                if (entry.uuid.equals(playerUuid.toString())) {
                    entry.lastKnownName = playerName;
                    entry.lastSeen = System.currentTimeMillis();
                    found = true;
                    break;
                }
            }
        } else {
            index.players = new java.util.ArrayList<>();
        }

        if (!found) {
            PlayerIndex.PlayerEntry entry = new PlayerIndex.PlayerEntry();
            entry.uuid = playerUuid.toString();
            entry.lastKnownName = playerName;
            entry.lastSeen = System.currentTimeMillis();
            index.players.add(entry);
        }

        writeJsonAtomically(indexFile, index);
    }

    /**
     * 從 player-index.json 查詢玩家名稱。
     */
    public String lookupPlayerName(UUID playerUuid) throws IOException {
        Path indexFile = playerIndexPath();
        if (!Files.exists(indexFile)) return null;
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            PlayerIndex index = gson.fromJson(reader, PlayerIndex.class);
            if (index.players != null) {
                for (PlayerIndex.PlayerEntry entry : index.players) {
                    if (entry.uuid.equals(playerUuid.toString())) {
                        return entry.lastKnownName;
                    }
                }
            }
        }
        return null;
    }

    // ====================== public-index.json ======================

    /**
     * 讀取公開藍圖索引（若不存在則回傳空索引）。
     */
    public synchronized PublicIndex readPublicIndex() throws IOException {
        Path indexFile = publicIndexPath();
        if (!Files.exists(indexFile)) return new PublicIndex();
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            PublicIndex index = gson.fromJson(reader, PublicIndex.class);
            if (index == null) index = new PublicIndex();
            index.version = 2;
            if (index.blueprints == null) index.blueprints = new java.util.ArrayList<>();
            return index;
        }
    }

    /**
     * 寫入公開藍圖索引。
     */
    public synchronized void writePublicIndex(PublicIndex index) throws IOException {
        Path indexFile = publicIndexPath();
        writeJsonAtomically(indexFile, index);
    }

    // ====================== JSON 模型類別 ======================

    @SuppressWarnings("unused")
    static class PlayerIndex {
        int version = 1;
        java.util.List<PlayerEntry> players = new java.util.ArrayList<>();

        static class PlayerEntry {
            String uuid;
            String lastKnownName;
            long lastSeen;
        }
    }

    @SuppressWarnings("unused")
    static class PublicIndex {
        int version = 2;
        long lastUpdated = System.currentTimeMillis();
        java.util.List<PublicEntry> blueprints = new java.util.ArrayList<>();

        static class PublicEntry {
            String blueprintId;
            String name;
            @SerializedName(value = "submitterUUID", alternate = "authorUUID")
            String submitterUUID;
            @SerializedName(value = "submitterName", alternate = "authorName")
            String submitterName;
            int gridN;
            String blockKey;
            long createdTimestamp;
            int cellCount;
        }
    }
}
