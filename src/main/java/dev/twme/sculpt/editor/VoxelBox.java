package dev.twme.sculpt.editor;

import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * An axis-aligned box of voxels (1/16 block), inclusive of {@code min} and
 * exclusive of {@code max}. Voxel coordinates are independent of the editing
 * resolution, so a selection survives resolution changes.
 */
public record VoxelBox(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {

    public static final int VOXELS_PER_BLOCK = 16;

    public VoxelBox {
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            throw new IllegalArgumentException("empty voxel box");
        }
    }

    /** A cube of {@code side} voxels starting at the given corner. */
    public static VoxelBox of(final long x, final long y, final long z, final int side) {
        return new VoxelBox(x, y, z, x + side, y + side, z + side);
    }

    /** The smallest box containing both boxes. */
    public VoxelBox union(final VoxelBox other) {
        return new VoxelBox(
            Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
            Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    /** Grow ({@code amount > 0}) or shrink every face; returns {@code this} if it would vanish. */
    public VoxelBox expand(final long amount) {
        final long nx = minX - amount;
        final long ny = minY - amount;
        final long nz = minZ - amount;
        final long xx = maxX + amount;
        final long xy = maxY + amount;
        final long xz = maxZ + amount;
        if (xx <= nx || xy <= ny || xz <= nz) return this;
        return new VoxelBox(nx, ny, nz, xx, xy, xz);
    }

    public VoxelBox offset(final long dx, final long dy, final long dz) {
        return new VoxelBox(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public long sizeX() {
        return maxX - minX;
    }

    public long sizeY() {
        return maxY - minY;
    }

    public long sizeZ() {
        return maxZ - minZ;
    }

    public long volume() {
        return sizeX() * sizeY() * sizeZ();
    }

    public boolean contains(final long x, final long y, final long z) {
        return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
    }

    public int minBlockX() {
        return (int) Math.floorDiv(minX, VOXELS_PER_BLOCK);
    }

    public int minBlockY() {
        return (int) Math.floorDiv(minY, VOXELS_PER_BLOCK);
    }

    public int minBlockZ() {
        return (int) Math.floorDiv(minZ, VOXELS_PER_BLOCK);
    }

    public int maxBlockX() {
        return (int) Math.floorDiv(maxX - 1, VOXELS_PER_BLOCK);
    }

    public int maxBlockY() {
        return (int) Math.floorDiv(maxY - 1, VOXELS_PER_BLOCK);
    }

    public int maxBlockZ() {
        return (int) Math.floorDiv(maxZ - 1, VOXELS_PER_BLOCK);
    }

    /** A voxel length in blocks, without a trailing ".0" for whole blocks. */
    public static String blocks(final long voxels) {
        if (voxels % VOXELS_PER_BLOCK == 0) return Long.toString(voxels / VOXELS_PER_BLOCK);
        final String text = Double.toString(voxels / (double) VOXELS_PER_BLOCK);
        return text.length() > 6 ? String.format(java.util.Locale.ROOT, "%.4f", voxels / 16.0) : text;
    }

    public Vector3f min() {
        return new Vector3f(minX / 16f, minY / 16f, minZ / 16f);
    }

    public Vector3f max() {
        return new Vector3f(maxX / 16f, maxY / 16f, maxZ / 16f);
    }

    public Vector3d center() {
        return new Vector3d((minX + maxX) / 32.0, (minY + maxY) / 32.0, (minZ + maxZ) / 32.0);
    }
}
