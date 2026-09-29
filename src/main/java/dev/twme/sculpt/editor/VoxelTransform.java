package dev.twme.sculpt.editor;

/**
 * Maps voxels of a source box to a destination: optional mirroring along X
 * and Z, a clockwise rotation around the Y axis in quarter turns (viewed from
 * above), then a translation of the box's minimum corner.
 */
public record VoxelTransform(
        VoxelBox source,
        int quarterTurns,
        boolean mirrorX,
        boolean mirrorZ,
        long offsetX,
        long offsetY,
        long offsetZ
) {

    public VoxelTransform {
        quarterTurns = Math.floorMod(quarterTurns, 4);
    }

    public static VoxelTransform identity(final VoxelBox source) {
        return new VoxelTransform(source, 0, false, false, 0, 0, 0);
    }

    public boolean isIdentity() {
        return quarterTurns == 0 && !mirrorX && !mirrorZ
            && offsetX == 0 && offsetY == 0 && offsetZ == 0;
    }

    /** The box the transformed voxels occupy. */
    public VoxelBox destination() {
        final boolean swap = quarterTurns % 2 == 1;
        final long sizeX = swap ? source.sizeZ() : source.sizeX();
        final long sizeZ = swap ? source.sizeX() : source.sizeZ();
        final long minX = source.minX() + offsetX;
        final long minY = source.minY() + offsetY;
        final long minZ = source.minZ() + offsetZ;
        return new VoxelBox(minX, minY, minZ, minX + sizeX, minY + source.sizeY(), minZ + sizeZ);
    }

    /** The destination of one absolute source voxel, as {@code {x, y, z}}. */
    public long[] apply(final long x, final long y, final long z) {
        final long sizeX = source.sizeX();
        final long sizeZ = source.sizeZ();
        long lx = x - source.minX();
        long lz = z - source.minZ();
        if (mirrorX) lx = sizeX - 1 - lx;
        if (mirrorZ) lz = sizeZ - 1 - lz;
        long rx = lx;
        long rz = lz;
        switch (quarterTurns) {
            case 1 -> {
                rx = sizeZ - 1 - lz;
                rz = lx;
            }
            case 2 -> {
                rx = sizeX - 1 - lx;
                rz = sizeZ - 1 - lz;
            }
            case 3 -> {
                rx = lz;
                rz = sizeX - 1 - lx;
            }
            default -> {
            }
        }
        final VoxelBox destination = destination();
        return new long[]{destination.minX() + rx, y + offsetY, destination.minZ() + rz};
    }
}
