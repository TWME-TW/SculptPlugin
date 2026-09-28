package dev.twme.sculpt.building;

import java.util.Comparator;

/** Integer world block coordinate used by building operations. */
public record BlockPos(int x, int y, int z) {

    /** Chunk-major order so region tasks visit one chunk at a time. */
    public static final Comparator<BlockPos> CHUNK_ORDER = Comparator
        .comparingInt((BlockPos pos) -> pos.x() >> 4)
        .thenComparingInt(pos -> pos.z() >> 4)
        .thenComparingInt(BlockPos::y)
        .thenComparingInt(BlockPos::x)
        .thenComparingInt(BlockPos::z);

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public boolean sameChunk(final BlockPos other) {
        return other != null && chunkX() == other.chunkX()
            && chunkZ() == other.chunkZ();
    }
}
