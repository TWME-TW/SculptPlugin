package dev.twme.sculpt.core;

/**
 * Position of a sub-block chunk in the N×N×N split grid. Coordinates are
 * 0-indexed; {@code (0,0,0)} is the corner where every {@link FaceDir} that
 * has a -1 component is outward (i.e. the (DOWN, NORTH, WEST) corner).
 *
 * <p>The natural order is {@code (y, z, x)} — y-major so iterating all chunks
 * walks a horizontal slice at a time, which the SkinPacker relies on for
 * deterministic upload ordering.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.core.ChunkCoord}).
 * See {@code DEVELOPMENT_PLAN.md} §4.3.
 */
public record ChunkCoord(int x, int y, int z) implements Comparable<ChunkCoord> {

    public ChunkCoord {
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException(
                    "ChunkCoord components must be non-negative: " + x + "," + y + "," + z);
        }
    }

    /** Stable string form for use as a map key (no Bukkit / JSON dependency). */
    public String asKey() {
        return x + "," + y + "," + z;
    }

    public static ChunkCoord parseKey(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("ChunkCoord key must be 'x,y,z': " + s);
        }
        return new ChunkCoord(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    /**
     * Canonical ordering: {@code y → z → x}. Used for deterministic iteration
     * over chunk grids.
     */
    @Override
    public int compareTo(ChunkCoord o) {
        int c = Integer.compare(y, o.y);
        if (c != 0) return c;
        c = Integer.compare(z, o.z);
        if (c != 0) return c;
        return Integer.compare(x, o.x);
    }
}
