package dev.twme.sculpt.blueprint;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * FolderManager — 管理藍圖的資料夾分類系統。
 * <p>
 * 每個玩家目錄下有一個獨立的 folders.json，記錄該玩家的自訂資料夾結構。
 * 資料夾使用 hash 作為實體名稱，顯示名稱由 folders.json 對照表決定。
 */
public class FolderManager {

    private static final int DEFAULT_MAX_DEPTH = 3;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final BlueprintIO io;
    private volatile int maxDepth;

    public FolderManager(BlueprintIO io) {
        this(io, DEFAULT_MAX_DEPTH);
    }

    public FolderManager(BlueprintIO io, int maxDepth) {
        this.io = io;
        setMaxDepth(maxDepth);
    }

    /** Update the validation limit without discarding folder state. */
    public void setMaxDepth(int maxDepth) {
        this.maxDepth = Math.max(1, maxDepth);
    }

    /**
     * 在玩家的私人目錄中建立一個資料夾。
     *
     * @param playerUuid 玩家 UUID
     * @param path 資料夾路徑，如 "城堡系列/中世紀"
     * @return 建立的資料夾 Folder 物件
     * @throws IOException 若檔案讀寫失敗
     * @throws IllegalArgumentException 若深度超過限制或路徑無效
     */
    public Folder createFolder(UUID playerUuid, String path) throws IOException {
        String[] parts = validatedParts(path);

        FolderIndex index = readFolderIndex(playerUuid, false);
        if (index == null) {
            index = new FolderIndex();
        }

        String parentHash = null;
        Folder lastCreated = null;

        for (String part : parts) {
            Folder existing = findChild(index, parentHash, part);
            if (existing != null) {
                lastCreated = existing;
                parentHash = existing.hash;
                continue;
            }
            String hash = generateFolderHash(index);
            Folder folder = new Folder(hash, part, parentHash);
            index.folders.add(folder);
            lastCreated = folder;
            parentHash = hash;
        }

        writeFolderIndex(playerUuid, index, false);
        return lastCreated;
    }

    /**
     * 重新命名資料夾（只改顯示名稱，不影響實體路徑）。
     */
    public boolean renameFolder(UUID playerUuid, String path, String newName) throws IOException {
        FolderIndex index = readFolderIndex(playerUuid, false);
        if (index == null) return false;

        String[] parts = path.split("/");
        Folder folder = resolveFolder(index, parts);
        if (folder == null) return false;

        folder.name = newName;
        writeFolderIndex(playerUuid, index, false);
        return true;
    }

    /**
     * 刪除資料夾（及其所有子資料夾）。
     */
    public boolean deleteFolder(UUID playerUuid, String path) throws IOException {
        FolderIndex index = readFolderIndex(playerUuid, false);
        if (index == null) return false;

        String[] parts = path.split("/");
        Folder folder = resolveFolder(index, parts);
        if (folder == null) return false;

        // 遞迴刪除子資料夾
        List<Folder> toRemove = new ArrayList<>();
        collectSubtree(index, folder.hash, toRemove);
        toRemove.add(folder);
        java.util.Set<String> removedHashes = toRemove.stream()
            .map(f -> f.hash).collect(java.util.stream.Collectors.toSet());
        index.blueprintFolders.entrySet().removeIf(e -> removedHashes.contains(e.getValue()));
        index.folders.removeAll(toRemove);

        writeFolderIndex(playerUuid, index, false);
        return true;
    }

    /**
     * 列出指定父資料夾下的子資料夾。
     */
    public List<FolderInfo> listFolders(UUID playerUuid, String parentPath, boolean isPublic) throws IOException {
        FolderIndex index = readFolderIndex(playerUuid, isPublic);
        if (index == null) return List.of();

        String parentHash = null;
        if (parentPath != null && !parentPath.isEmpty()) {
            String[] parts = parentPath.split("/");
            Folder parent = resolveFolder(index, parts);
            if (parent == null) return List.of();
            parentHash = parent.hash;
        }

        List<FolderInfo> result = new ArrayList<>();
        for (Folder folder : index.folders) {
            if ((parentHash == null && folder.parent == null)
                || (parentHash != null && parentHash.equals(folder.parent))) {
                result.add(new FolderInfo(folder.name, folder.hash, folder.parent));
            }
        }
        return result;
    }

    /** Associate a blueprint with a logical folder without exposing the folder name as a disk path. */
    public void assignBlueprint(UUID playerUuid, UUID blueprintId, String path,
                                boolean isPublic) throws IOException {
        String[] parts = validatedParts(path);
        FolderIndex index = normalizedIndex(readFolderIndex(playerUuid, isPublic));
        Folder folder = resolveFolder(index, parts);
        if (folder == null) {
            folder = createFolder(playerUuid, path, isPublic);
            index = normalizedIndex(readFolderIndex(playerUuid, isPublic));
            folder = resolveFolder(index, parts);
        }
        if (folder == null) throw new IOException("Could not resolve folder after creation");
        index.blueprintFolders.put(blueprintId.toString(), folder.hash);
        writeFolderIndex(playerUuid, index, isPublic);
    }

    /** Remove a deleted blueprint from the logical folder index. */
    public void removeBlueprint(UUID playerUuid, UUID blueprintId, boolean isPublic) throws IOException {
        FolderIndex index = readFolderIndex(playerUuid, isPublic);
        if (index == null || index.blueprintFolders == null) return;
        if (index.blueprintFolders.remove(blueprintId.toString()) != null) {
            writeFolderIndex(playerUuid, index, isPublic);
        }
    }

    /** Return whether a blueprint belongs directly to the requested folder (null means root). */
    public boolean isBlueprintInFolder(UUID playerUuid, UUID blueprintId,
                                       @javax.annotation.Nullable String path,
                                       boolean isPublic) throws IOException {
        FolderIndex index = normalizedIndex(readFolderIndex(playerUuid, isPublic));
        String assigned = index.blueprintFolders.get(blueprintId.toString());
        if (path == null || path.isBlank()) return assigned == null;
        Folder folder = resolveFolder(index, validatedParts(path));
        return folder != null && folder.hash.equals(assigned);
    }

    private Folder createFolder(UUID playerUuid, String path, boolean isPublic) throws IOException {
        String[] parts = validatedParts(path);
        FolderIndex index = normalizedIndex(readFolderIndex(playerUuid, isPublic));
        String parentHash = null;
        Folder current = null;
        for (String part : parts) {
            current = findChild(index, parentHash, part);
            if (current == null) {
                current = new Folder(generateFolderHash(index), part, parentHash);
                index.folders.add(current);
            }
            parentHash = current.hash;
        }
        writeFolderIndex(playerUuid, index, isPublic);
        return current;
    }

    // ====================== 內部方法 ======================

    private FolderIndex readFolderIndex(UUID playerUuid, boolean isPublic) throws IOException {
        Path indexFile = isPublic
            ? io.publicPlayerDir(playerUuid).resolve("folders.json")
            : io.privatePlayerDir(playerUuid).resolve("folders.json");
        if (!Files.exists(indexFile)) return null;
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            try {
                return normalizedIndex(GSON.fromJson(reader, FolderIndex.class));
            } catch (RuntimeException e) {
                throw new IOException("Malformed folders.json", e);
            }
        }
    }

    private void writeFolderIndex(UUID playerUuid, FolderIndex index, boolean isPublic) throws IOException {
        Path dir = isPublic ? io.publicPlayerDir(playerUuid) : io.privatePlayerDir(playerUuid);
        Files.createDirectories(dir);
        Path indexFile = dir.resolve("folders.json");
        Path temporary = Files.createTempFile(dir, "folders-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(normalizedIndex(index), writer);
            }
            try {
                Files.move(temporary, indexFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String[] validatedParts(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Folder path is empty");
        }
        String[] parts = path.split("/", -1);
        if (parts.length > maxDepth) {
            throw new IllegalArgumentException("Folder depth exceeds " + maxDepth);
        }
        for (String part : parts) {
            if (part.isBlank() || part.equals(".") || part.equals("..")
                    || part.length() > 64 || part.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid folder segment");
            }
        }
        return parts;
    }

    private static FolderIndex normalizedIndex(FolderIndex index) {
        if (index == null) index = new FolderIndex();
        if (index.folders == null) index.folders = new ArrayList<>();
        if (index.blueprintFolders == null) index.blueprintFolders = new HashMap<>();
        return index;
    }

    private static Folder findChild(FolderIndex index, String parentHash, String name) {
        for (Folder folder : index.folders) {
            boolean sameParent = parentHash == null ? folder.parent == null
                : parentHash.equals(folder.parent);
            if (sameParent && name.equals(folder.name)) return folder;
        }
        return null;
    }

    private String generateFolderHash(FolderIndex index) {
        String hash;
        int attempts = 0;
        do {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(Integer.toHexString(RANDOM.nextInt(16)));
            }
            hash = sb.toString();
            attempts++;
        } while (isHashTaken(index, hash) && attempts < 100);
        return hash;
    }

    private boolean isHashTaken(FolderIndex index, String hash) {
        for (Folder f : index.folders) {
            if (f.hash.equals(hash)) return true;
        }
        return false;
    }

    /** 依照路徑解析 Folder，深度優先。 */
    private Folder resolveFolder(FolderIndex index, String[] pathParts) {
        List<Folder> currentLevel = index.folders.stream()
            .filter(f -> f.parent == null).toList();

        Folder current = null;
        for (String part : pathParts) {
            boolean found = false;
            for (Folder f : currentLevel) {
                if (f.name.equals(part)) {
                    current = f;
                    String parentHash = f.hash;
                    currentLevel = index.folders.stream()
                        .filter(c -> parentHash.equals(c.parent)).toList();
                    found = true;
                    break;
                }
            }
            if (!found) return null;
        }
        return current;
    }

    private void collectSubtree(FolderIndex index, String parentHash, List<Folder> out) {
        for (Folder f : index.folders) {
            if (parentHash.equals(f.parent)) {
                collectSubtree(index, f.hash, out);
                out.add(f);
            }
        }
    }

    // ====================== 資料模型 ======================

    static class FolderIndex {
        int version = 2;
        List<Folder> folders = new ArrayList<>();
        Map<String, String> blueprintFolders = new HashMap<>();
    }

    static class Folder {
        String hash;         // 實體資料夾名稱（8 hex chars）
        String name;         // 顯示名稱（玩家可自訂）
        long created;        // 建立時間戳
        String parent;       // 上層資料夾 hash，null = 根層級

        Folder() {}

        Folder(String hash, String name, String parent) {
            this.hash = hash;
            this.name = name;
            this.created = System.currentTimeMillis();
            this.parent = parent;
        }
    }

    /** 公開的資料夾資訊（不含 hash 細節）。 */
    public record FolderInfo(String name, String hash, String parentHash) {}
}
