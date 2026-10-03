package dev.twme.sculpt.editor;

import org.joml.Vector2d;

/**
 * A rotation around the vertical axis, in voxel space.
 *
 * <p>The forward transform is {@code p' = R(theta) * S * (p - pivot) + pivot}:
 * the point is mirrored around the pivot first (optionally on both horizontal
 * axes) and then rotated counter-clockwise when viewed from above. The
 * inverse undoes the rotation first and the mirroring second, which is not
 * the same composition as the forward transform because a rotation and a
 * mirror do not commute.</p>
 *
 * <p>Any angle is allowed. Whole quarter turns are evaluated with integer
 * arithmetic, so their results are exact and voxels always land on the grid;
 * {@link #isQuarterTurn()} reports whether that path applies.</p>
 */
public record VoxelRotation(double angle, boolean mirrorX, boolean mirrorZ, double pivotX, double pivotZ) {

    /** Angles closer to a multiple of 90° than this use the exact integer path. */
    private static final double QUARTER_EPSILON = 1e-9;

    public VoxelRotation {
        angle = normalize(angle);
    }

    /** A rotation of {@code angle} radians around a box's horizontal center. */
    public static VoxelRotation around(final VoxelBox box, final double angle,
                                       final boolean mirrorX, final boolean mirrorZ) {
        final Vector2d center = center(box);
        return new VoxelRotation(angle, mirrorX, mirrorZ, center.x, center.y);
    }

    public static VoxelRotation identity(final VoxelBox box) {
        return around(box, 0.0, false, false);
    }

    /** The horizontal center of a box, which is also its rotation pivot. */
    public static Vector2d center(final VoxelBox box) {
        return new Vector2d((box.minX() + box.maxX()) / 2.0, (box.minZ() + box.maxZ()) / 2.0);
    }

    public boolean isIdentity() {
        return isZero(angle) && !mirrorX && !mirrorZ;
    }

    /** Whether the angle is a whole number of quarter turns. */
    public boolean isQuarterTurn() {
        final double quarters = angle / (Math.PI / 2);
        return Math.abs(quarters - Math.round(quarters)) < QUARTER_EPSILON;
    }

    /** The angle as whole quarter turns; only meaningful when {@link #isQuarterTurn()}. */
    public int quarterTurns() {
        return (int) Math.round(angle / (Math.PI / 2));
    }

    // =====================================================================
    //  Point transforms
    // =====================================================================

    /** Mirrors and then rotates one horizontal point. */
    public double[] apply(final double x, final double z) {
        double dx = x - pivotX;
        double dz = z - pivotZ;
        if (mirrorX) dx = -dx;
        if (mirrorZ) dz = -dz;
        if (!isZero(angle)) {
            final double cos = Math.cos(angle);
            final double sin = Math.sin(angle);
            final double rx = dx * cos - dz * sin;
            final double rz = dx * sin + dz * cos;
            dx = rx;
            dz = rz;
        }
        return new double[]{dx + pivotX, dz + pivotZ};
    }

    /** The point that {@link #apply} maps onto {@code (x, z)}. */
    public double[] inverseApply(final double x, final double z) {
        double dx = x - pivotX;
        double dz = z - pivotZ;
        if (!isZero(angle)) {
            final double cos = Math.cos(-angle);
            final double sin = Math.sin(-angle);
            final double rx = dx * cos - dz * sin;
            final double rz = dx * sin + dz * cos;
            dx = rx;
            dz = rz;
        }
        if (mirrorX) dx = -dx;
        if (mirrorZ) dz = -dz;
        return new double[]{dx + pivotX, dz + pivotZ};
    }

    // =====================================================================
    //  Voxel transforms
    // =====================================================================

    /**
     * The voxel the source voxel's center lands in. Quarter turns use exact
     * integer arithmetic; other angles round the rotated center down, which
     * is a nearest-neighbour resampling.
     */
    public long[] applyVoxel(final long x, final long z) {
        if (isQuarterTurn()) {
            final long doubled = 2 * x + 1;
            final long doubledZ = 2 * z + 1;
            return quarterTurnVoxel(doubled, doubledZ);
        }
        final double[] point = apply(x + 0.5, z + 0.5);
        return new long[]{(long) Math.floor(point[0]), (long) Math.floor(point[1])};
    }

    /**
     * The source voxel whose center maps into the destination voxel. Only
     * meaningful when the result lies inside the source box.
     */
    public long[] inverseVoxel(final long x, final long z) {
        if (isQuarterTurn()) {
            final long doubled = 2 * x + 1;
            final long doubledZ = 2 * z + 1;
            return quarterTurnVoxelInverse(doubled, doubledZ);
        }
        final double[] point = inverseApply(x + 0.5, z + 0.5);
        return new long[]{(long) Math.floor(point[0]), (long) Math.floor(point[1])};
    }

    /** The forward map in doubled voxel-center coordinates, using only integers. */
    private long[] quarterTurnVoxel(final long doubledX, final long doubledZ) {
        final long pivot2X = Math.round(2 * pivotX);
        final long pivot2Z = Math.round(2 * pivotZ);
        long dx = doubledX - pivot2X;
        long dz = doubledZ - pivot2Z;
        if (mirrorX) dx = -dx;
        if (mirrorZ) dz = -dz;
        final long[] rotated = rotateQuarters(dx, dz, quarterTurns());
        return new long[]{Math.floorDiv(pivot2X + rotated[0], 2),
            Math.floorDiv(pivot2Z + rotated[1], 2)};
    }

    /** The inverse map in doubled voxel-center coordinates, using only integers. */
    private long[] quarterTurnVoxelInverse(final long doubledX, final long doubledZ) {
        final long pivot2X = Math.round(2 * pivotX);
        final long pivot2Z = Math.round(2 * pivotZ);
        final long dx = doubledX - pivot2X;
        final long dz = doubledZ - pivot2Z;
        // Undo the rotation first, then the mirroring.
        final long[] unrotated = rotateQuarters(dx, dz, -quarterTurns());
        long ux = unrotated[0];
        long uz = unrotated[1];
        if (mirrorX) ux = -ux;
        if (mirrorZ) uz = -uz;
        return new long[]{Math.floorDiv(pivot2X + ux, 2), Math.floorDiv(pivot2Z + uz, 2)};
    }

    /** Counter-clockwise quarter turns in doubled coordinates. */
    private static long[] rotateQuarters(final long x, final long z, final int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new long[]{-z, x};
            case 2 -> new long[]{-x, -z};
            case 3 -> new long[]{z, -x};
            default -> new long[]{x, z};
        };
    }

    // =====================================================================
    //  Destination
    // =====================================================================

    /**
     * The smallest whole-voxel box containing the transformed source box, so
     * every transformed voxel still has somewhere to land.
     */
    public VoxelBox destination(final VoxelBox source) {
        if (isIdentity()) return source;
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (final double[] corner : corners(source)) {
            final double[] point = apply(corner[0], corner[1]);
            minX = Math.min(minX, point[0]);
            minZ = Math.min(minZ, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxZ = Math.max(maxZ, point[1]);
        }
        // Nudge outwards so a corner that lands exactly on a whole coordinate
        // (the common case for quarter turns) does not lose a voxel to rounding.
        final long x0 = (long) Math.floor(minX + QUARTER_EPSILON);
        final long z0 = (long) Math.floor(minZ + QUARTER_EPSILON);
        final long x1 = Math.max((long) Math.ceil(maxX - QUARTER_EPSILON), x0 + 1);
        final long z1 = Math.max((long) Math.ceil(maxZ - QUARTER_EPSILON), z0 + 1);
        return new VoxelBox(x0, source.minY(), z0, x1, source.maxY(), z1);
    }

    /** The four horizontal corners of a box. */
    private static double[][] corners(final VoxelBox box) {
        return new double[][]{
            {box.minX(), box.minZ()}, {box.maxX(), box.minZ()},
            {box.maxX(), box.maxZ()}, {box.minX(), box.maxZ()},
        };
    }

    /** Fold any angle into {@code [0, 2*pi)}. */
    private static double normalize(final double angle) {
        if (isZero(angle)) return 0.0;
        final double turn = Math.PI * 2;
        final double folded = angle % turn;
        return folded < 0 ? folded + turn : folded;
    }

    private static boolean isZero(final double angle) {
        return Math.abs(angle) < QUARTER_EPSILON;
    }
}
