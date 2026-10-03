package dev.twme.sculpt.editor;

/**
 * Maps the voxels of a source box to a destination: a horizontal rotation
 * around the box center with optional mirroring, then a translation.
 *
 * <p>The destination box is derived, not configured: it is the smallest
 * whole-voxel box containing the transformed source, shifted by the
 * translation. Rotation happens before translation, so the rotation pivot
 * stays the center of the original selection however far it is dragged.</p>
 */
public record VoxelTransform(VoxelRotation rotation, long offsetX, long offsetY, long offsetZ) {

    public static VoxelTransform identity(final VoxelBox source) {
        return new VoxelTransform(VoxelRotation.identity(source), 0, 0, 0);
    }

    public boolean isIdentity() {
        return rotation.isIdentity() && offsetX == 0 && offsetY == 0 && offsetZ == 0;
    }

    /** The box the transformed voxels occupy, including the translation. */
    public VoxelBox destination(final VoxelBox source) {
        final VoxelBox rotated = rotation.destination(source);
        return rotated.offset(offsetX, offsetY, offsetZ);
    }

    /** The destination of one source voxel, as {@code {x, y, z}}. */
    public long[] apply(final long x, final long y, final long z) {
        final long[] horizontal = rotation.applyVoxel(x, z);
        return new long[]{horizontal[0] + offsetX, y + offsetY, horizontal[1] + offsetZ};
    }

    /**
     * The source voxel that lands in the given destination voxel, or
     * {@code null} when the destination is not covered by the source box.
     */
    public long[] inverse(final long x, final long y, final long z, final VoxelBox source) {
        final long uy = y - offsetY;
        if (uy < source.minY() || uy >= source.maxY()) return null;
        final long[] horizontal = rotation.inverseVoxel(x - offsetX, z - offsetZ);
        final long ux = horizontal[0];
        final long uz = horizontal[1];
        if (!source.contains(ux, uy, uz)) return null;
        return new long[]{ux, uy, uz};
    }
}
