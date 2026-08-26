package dev.twme.sculpt.blueprint;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * 藍圖資料模型。一個舊格式藍圖代表單一 SculptBlock；新格式可在
 * {@code blocks} 中保存長方體選區內的多個 SculptBlock。
 * <p>
 * 此 record 包含雕刻結構的完整狀態：八元樹拓樸、材質、變體旋轉等。
 * 用於儲存為 .blueprint 檔案或上傳至 SculptWeb API。
 *
 * @param blueprintId          唯一識別碼（UUID v4）
 * @param name                 玩家自訂名稱（可重複，1-64 字元）
 * @param description          描述文字（可選，用於公開分享時）
 * @param createdTimestamp     建立時間戳（epoch ms）
 * @param lastModifiedTimestamp 最後修改時間戳（epoch ms）
 * @param minecraftVersion     建立時的 MC 版本，如 "1.21.11"
 * @param blockKey             原始方塊類型（若為混合材質則為 null），格式 "minecraft:stone"
 * @param matchedVariantKey    變體鍵，如 "axis=y"
 * @param isMixed              是否為混合材質
 * @param maxDepth             樹的最大深度（gridN = 1 &lt;&lt; maxDepth）
 * @param gridN                一邊的切割數（= 1 &lt;&lt; maxDepth）
 * @param octreeData           自訂二進位格式的八元樹資料（見 OctreeNode.serialize）
 * @param leafCoordinates      每片葉子的路徑 → 規範化 ChunkCoord [x, y, z]
 *                             用於貼上時還原正確的皮膚
 * @param blocks               多方塊快照；null 或空陣列代表舊格式單方塊藍圖
 * @param sizeX                選區 X 尺寸；舊格式缺少時視為 1
 * @param sizeY                選區 Y 尺寸；舊格式缺少時視為 1
 * @param sizeZ                選區 Z 尺寸；舊格式缺少時視為 1
 * @param referenceFacing      選取來源時玩家面向方塊的水平朝向，用於相對旋轉
 * @param visibility           分享設定（僅供參考，實際以網站為主）
 */
public record BlueprintData(
    // ----- 元資料 -----
    UUID blueprintId,
    String name,
    @Nullable String description,
    long createdTimestamp,
    long lastModifiedTimestamp,
    @Nullable String minecraftVersion,

    // ----- 雕刻資料 -----
    @Nullable String blockKey,
    @Nullable String matchedVariantKey,
    boolean isMixed,

    // ----- 八元樹資料 -----
    int maxDepth,
    int gridN,
    byte[] octreeData,

    // ----- Cell 座標（用於貼上時還原皮膚） -----
    @Nullable Map<String, int[]> leafCoordinates,

    // ----- 長方體選區（舊格式沒有這些欄位） -----
    @Nullable List<BlueprintBlockData> blocks,
    int sizeX,
    int sizeY,
    int sizeZ,

    // ----- 選取來源時的視角（用於 PLAYER / AUTO 相對旋轉） -----
    @Nullable String referenceFacing,

    // ----- 分享設定 -----
    @Nullable Visibility visibility,

    // ----- 舊格式相容欄位；新版會遷移到伺服器私有索引且不再輸出 -----
    @Nullable String editToken
) {

    /** Current single-block constructor retained for source and data compatibility. */
    public BlueprintData(
            UUID blueprintId,
            String name,
            @Nullable String description,
            long createdTimestamp,
            long lastModifiedTimestamp,
            @Nullable String minecraftVersion,
            @Nullable String blockKey,
            @Nullable String matchedVariantKey,
            boolean isMixed,
            int maxDepth,
            int gridN,
            byte[] octreeData,
            @Nullable Map<String, int[]> leafCoordinates,
            @Nullable String referenceFacing,
            @Nullable Visibility visibility,
            @Nullable String editToken) {
        this(blueprintId, name, description, createdTimestamp, lastModifiedTimestamp,
            minecraftVersion, blockKey, matchedVariantKey, isMixed, maxDepth, gridN,
            octreeData, leafCoordinates, null, 1, 1, 1, referenceFacing,
            visibility, editToken);
    }

    /**
     * 舊格式相容建構子。既有藍圖沒有 referenceFacing，貼上時會沿用舊版的
     * 絕對朝向解讀。
     */
    public BlueprintData(
            UUID blueprintId,
            String name,
            @Nullable String description,
            long createdTimestamp,
            long lastModifiedTimestamp,
            @Nullable String minecraftVersion,
            @Nullable String blockKey,
            @Nullable String matchedVariantKey,
            boolean isMixed,
            int maxDepth,
            int gridN,
            byte[] octreeData,
            @Nullable Map<String, int[]> leafCoordinates,
            @Nullable Visibility visibility,
            @Nullable String editToken) {
        this(blueprintId, name, description, createdTimestamp, lastModifiedTimestamp,
            minecraftVersion, blockKey, matchedVariantKey, isMixed, maxDepth, gridN,
            octreeData, leafCoordinates, null, visibility, editToken);
    }

    /** A single SculptBlock always occupies one full Minecraft block. */
    public static final int SIDE_LENGTH = 1;

    public enum Visibility {
        PUBLIC,
        UNLIST,
        SECRET,
        /** 僅儲存在本機，不分享到網站 */
        PRIVATE
    }

    /** 每個 cell 的大小 = SIDE_LENGTH / gridN */
    public double cellSize() {
        return (double) SIDE_LENGTH / gridN;
    }

    public boolean hasBlockCollection() {
        return blocks != null && !blocks.isEmpty();
    }

    public int effectiveSizeX() {
        return hasBlockCollection() ? sizeX : 1;
    }

    public int effectiveSizeY() {
        return hasBlockCollection() ? sizeY : 1;
    }

    public int effectiveSizeZ() {
        return hasBlockCollection() ? sizeZ : 1;
    }
}
