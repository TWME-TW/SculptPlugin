package dev.twme.sculpt.core;

import java.util.Map;
import java.util.function.BiConsumer;

import org.bukkit.block.data.BlockData;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.twme.sculpt.skin.HeadsRegistry;
import dev.twme.sculpt.skin.bake.BlockBaker;

/**
 * 根據葉子的 Octree 資訊解析其頭顱 ItemStack 和 Transformation。
 * 每個葉子根據其位置/深度取得獨立的材質。
 */
public interface HeadResolver {

    /**
     * 為節點 node 解析頭顱。
     * @param node  葉子節點（呼叫端保證 node.isLeaf()）
     * @param block SculptBlock（提供 blockRotation、originalBlockData）
     * @return 頭顱資訊，永不為 null（unbaked 時回傳
     *         {@link ChunkHead#placeholder()} 為 true 的佔位內容）
     */
    ChunkHead headFor(OctreeNode node, SculptBlock block);

    /**
     * 解析 BlockData 的變體旋轉。
     * @param data  BlockData
     * @param gridN 目標 gridN
     * @return 解析結果
     */
    default VariantResolution resolveVariant(BlockData data, int gridN) {
        return new VariantResolution(new Quaternionf(), "");
    }

    /**
     * 將物理 cell 座標透過方塊旋轉映射為規範化座標（用於 HeadsRegistry 查詢）。
     * <p>
     * 應用 blockRotation 的共軛（單位四元數的逆）來轉換座標，
     * 使紋理網格隨方塊旋轉，保持貼圖的一致性。
     *
     * @param coord    physical ChunkCoord
     * @param rotation 方塊的四元數旋轉（從 canonical → oriented 的映射）
     * @param gridN    grid 大小（如 16）
     * @return 規範化的 ChunkCoord（用於 HeadsRegistry 查詢 skin）
     */
    static ChunkCoord rotateCoord(ChunkCoord coord, Quaternionf rotation, int gridN) {
        if (rotation.equals(new Quaternionf()) || rotation.equals(new Quaternionf(0, 0, 0, 1))) {
            return coord;
        }
        float half = gridN / 2.0f;
        Vector3f v = new Vector3f(
            coord.x() + 0.5f - half,
            coord.y() + 0.5f - half,
            coord.z() + 0.5f - half
        );
        new Quaternionf(rotation).conjugate().transform(v);
        int gx = Math.round(v.x + half - 0.5f);
        int gy = Math.round(v.y + half - 0.5f);
        int gz = Math.round(v.z + half - 0.5f);
        gx = Math.max(0, Math.min(gridN - 1, gx));
        gy = Math.max(0, Math.min(gridN - 1, gy));
        gz = Math.max(0, Math.min(gridN - 1, gz));
        return new ChunkCoord(gx, gy, gz);
    }

    /** Resolve the canonical texture cell used by both head and text renderers. */
    static ChunkCoord textureCoordFor(
            final OctreeNode node,
            final SculptBlock block) {
        final int gridN = 1 << node.depth();
        if (node.textureCoord() != null) {
            return rotateCoord(node.textureCoord(), block.blockRotation, gridN);
        }
        if (block.storedCoords != null) {
            final int[] stored = block.storedCoords.get(node.pathAsString());
            if (stored != null) {
                return new ChunkCoord(stored[0], stored[1], stored[2]);
            }
        }
        final int side = node.side();
        return rotateCoord(new ChunkCoord(
            node.minX() / side,
            node.minY() / side,
            node.minZ() / side), block.blockRotation, gridN);
    }

    /**
     * 建立一個依賴多個 HeadsRegistry 的 HeadResolver（每個 grid 大小一個）。
     * 葉子節點會自動使用對應 gridN 的 registry 和 baker。
     *
     * @param registriesByGridN  gridN → HeadsRegistry
     * @param bakersByGridN      gridN → BlockBaker
     * @param onBakeTriggered    當 headFor 觸發 batch bake 時回呼（用於註冊 pending 追蹤）
     */
    static HeadResolver fromRegistry(Map<Integer, HeadsRegistry> registriesByGridN,
                                     Map<Integer, BlockBaker> bakersByGridN,
                                     BiConsumer<BlockBaker.Batch, SculptBlock> onBakeTriggered) {
        return fromRegistry(registriesByGridN, bakersByGridN, onBakeTriggered, null);
    }

    /**
     * Variant of {@link #fromRegistry(Map, Map, BiConsumer)} that receives a
     * callback when a known registry entry has finished loading asynchronously.
     * The callback is responsible for scheduling a region-thread re-render.
     */
    static HeadResolver fromRegistry(Map<Integer, HeadsRegistry> registriesByGridN,
                                     Map<Integer, BlockBaker> bakersByGridN,
                                     BiConsumer<BlockBaker.Batch, SculptBlock> onBakeTriggered,
                                     BiConsumer<SculptBlock, Runnable> onRegistryDataReady) {
        return new RegistryHeadResolver(registriesByGridN, bakersByGridN,
                onBakeTriggered, onRegistryDataReady);
    }
}
