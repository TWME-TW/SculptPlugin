package dev.twme.sculpt.editor;

import org.bukkit.block.Block;

import dev.twme.sculpt.core.FaceDir;

/**
 * 3D DDA 在 playerGrid 虛擬網格中的命中結果。
 *
 * @param pgx     虛擬單元 X 索引 (0..playerGrid-1)
 * @param pgy     虛擬單元 Y 索引
 * @param pgz     虛擬單元 Z 索引
 * @param face    射線進入該單元穿過的平面法向量
 * @param block   命中的世界方塊
 */
public record VirtualGridHit(
    int pgx, int pgy, int pgz,
    FaceDir face,
    Block block
) {
    /** 此虛擬單元在 grid=16 空間中的最小角落 X。 */
    public int grid16MinX(int playerGrid) { return pgx * (16 / playerGrid); }
    public int grid16MinY(int playerGrid) { return pgy * (16 / playerGrid); }
    public int grid16MinZ(int playerGrid) { return pgz * (16 / playerGrid); }
    public int grid16Side(int playerGrid) { return 16 / playerGrid; }

    /** 此虛擬單元在 grid=16 空間中的中心 X。 */
    public int grid16CenterX(int playerGrid) {
        return grid16MinX(playerGrid) + grid16Side(playerGrid) / 2;
    }
    public int grid16CenterY(int playerGrid) {
        return grid16MinY(playerGrid) + grid16Side(playerGrid) / 2;
    }
    public int grid16CenterZ(int playerGrid) {
        return grid16MinZ(playerGrid) + grid16Side(playerGrid) / 2;
    }
}
