package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Converts building shapes into world cells at one resolution.
 *
 * <p>All public inputs use world block coordinates. Internally every shape is
 * evaluated in cell space ({@code block * grid}), where cell centers are at
 * {@code n + 0.5}. Thin surfaces use conservative voxelization: a cell is kept
 * when the surface passes through it, which keeps one-cell-thick planes and
 * shells watertight at every angle.
 */
public final class ShapeRasterizer {

    private static final double EPSILON = 1e-9;
    private static final double HALF_DIAGONAL = Math.sqrt(3.0) / 2.0;
    /** Maximum tessellation segments per surface parameter direction. */
    static final int MAX_SURFACE_SEGMENTS = 512;

    public enum BrushShape { SPHERE, CUBE }

    private ShapeRasterizer() {}

    // =====================================================================
    //  Planes and surfaces
    // =====================================================================

    /**
     * Fill a polygon through the points using a triangle fan anchored at the
     * first point. Three points form an arbitrary-angle triangle; four or more
     * points form a (possibly folded) multi-angle plane.
     */
    public static void polygon(
            final List<? extends Vector3dc> points,
            final double thickness,
            final CellVolume output) {
        requireAtLeast(points, 3);
        final List<Vector3d> cells = toCellSpace(points, output.grid());
        final Vector3d anchor = cells.getFirst();
        for (int index = 1; index + 1 < cells.size(); index++) {
            triangle(anchor, cells.get(index), cells.get(index + 1),
                thickness, output);
        }
    }

    /**
     * Fill a Bézier surface patch whose control net is given in row-major
     * order. Two rows of two points form a bilinear (twisted) quad; three or
     * four rows/columns produce quadratic or cubic curvature.
     */
    public static void bezierSurface(
            final List<? extends Vector3dc> controlPoints,
            final int rows,
            final double thickness,
            final CellVolume output) {
        if (rows < 2 || controlPoints.size() % rows != 0
                || controlPoints.size() / rows < 2) {
            throw new IllegalArgumentException(
                "control points must form a grid with at least 2 rows and 2 columns");
        }
        final int columns = controlPoints.size() / rows;
        final List<Vector3d> net = toCellSpace(controlPoints, output.grid());
        final int segmentsU = segmentsFor(maxPolylineLength(net, rows, columns, true));
        final int segmentsV = segmentsFor(maxPolylineLength(net, rows, columns, false));

        final Vector3d[][] samples = new Vector3d[segmentsU + 1][segmentsV + 1];
        for (int i = 0; i <= segmentsU; i++) {
            final double u = (double) i / segmentsU;
            for (int j = 0; j <= segmentsV; j++) {
                samples[i][j] = evaluateBezierSurface(
                    net, rows, columns, u, (double) j / segmentsV);
            }
        }
        for (int i = 0; i < segmentsU; i++) {
            for (int j = 0; j < segmentsV; j++) {
                triangle(samples[i][j], samples[i + 1][j],
                    samples[i + 1][j + 1], thickness, output);
                triangle(samples[i][j], samples[i + 1][j + 1],
                    samples[i][j + 1], thickness, output);
            }
        }
    }

    /**
     * Sweep a tube through every point using a Catmull-Rom spline. Two
     * points produce a straight beam; more points produce a smooth curve.
     */
    public static void curve(
            final List<? extends Vector3dc> points,
            final double thickness,
            final CellVolume output) {
        requireAtLeast(points, 2);
        final List<Vector3d> cells = toCellSpace(points, output.grid());
        final List<Vector3d> samples = new ArrayList<>();
        samples.add(new Vector3d(cells.getFirst()));
        for (int index = 0; index + 1 < cells.size(); index++) {
            final Vector3d p0 = cells.get(Math.max(0, index - 1));
            final Vector3d p1 = cells.get(index);
            final Vector3d p2 = cells.get(index + 1);
            final Vector3d p3 = cells.get(Math.min(cells.size() - 1, index + 2));
            final int steps = Math.max(1, Math.min(4096,
                (int) Math.ceil(p1.distance(p2) * 2.0)));
            for (int step = 1; step <= steps; step++) {
                samples.add(catmullRom(p0, p1, p2, p3, (double) step / steps));
            }
        }
        for (int index = 0; index + 1 < samples.size(); index++) {
            segment(samples.get(index), samples.get(index + 1), thickness, output);
        }
        if (samples.size() == 1) segment(samples.getFirst(), samples.getFirst(),
            thickness, output);
    }

    // =====================================================================
    //  Solids and shells
    // =====================================================================

    /**
     * Sphere around {@code center}. A {@code shellThickness} of zero or less
     * produces a solid sphere; otherwise a shell centered on the surface.
     */
    public static void sphere(
            final Vector3dc center,
            final double radius,
            final double shellThickness,
            final CellVolume output) {
        final int grid = output.grid();
        final Vector3d c = new Vector3d(center).mul(grid);
        final double r = Math.max(0.0, radius * grid);
        final double margin = Math.max(shellThickness, 1.0) + 1.0;
        final long minX = (long) Math.floor(c.x - r - margin);
        final long maxX = (long) Math.ceil(c.x + r + margin);
        final long minY = (long) Math.floor(c.y - r - margin);
        final long maxY = (long) Math.ceil(c.y + r + margin);
        final long minZ = (long) Math.floor(c.z - r - margin);
        final long maxZ = (long) Math.ceil(c.z + r + margin);
        for (long x = minX; x <= maxX; x++) {
            final double dx = x + 0.5 - c.x;
            for (long y = minY; y <= maxY; y++) {
                final double dy = y + 0.5 - c.y;
                for (long z = minZ; z <= maxZ; z++) {
                    final double dz = z + 0.5 - c.z;
                    final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    final boolean keep;
                    if (shellThickness <= 0.0) {
                        keep = distance <= r + EPSILON || isContainingCell(dx, dy, dz);
                    } else {
                        keep = withinShell(distance - r, dx, dy, dz, distance,
                            shellThickness);
                    }
                    if (keep) output.add(x, y, z);
                }
            }
        }
    }

    /**
     * Cylinder whose axis runs from {@code start} to {@code end}. A
     * {@code shellThickness} of zero or less produces a solid cylinder with
     * flat caps; otherwise an open tube wall centered on the radius.
     */
    public static void cylinder(
            final Vector3dc start,
            final Vector3dc end,
            final double radius,
            final double shellThickness,
            final CellVolume output) {
        final int grid = output.grid();
        final Vector3d a = new Vector3d(start).mul(grid);
        final Vector3d b = new Vector3d(end).mul(grid);
        final Vector3d axis = new Vector3d(b).sub(a);
        final double length = axis.length();
        if (length < EPSILON) {
            throw new IllegalArgumentException("cylinder axis points must differ");
        }
        axis.div(length);
        final double r = Math.max(0.0, radius * grid);
        final double margin = r + Math.max(shellThickness, 1.0) + 1.0;
        final long minX = (long) Math.floor(Math.min(a.x, b.x) - margin);
        final long maxX = (long) Math.ceil(Math.max(a.x, b.x) + margin);
        final long minY = (long) Math.floor(Math.min(a.y, b.y) - margin);
        final long maxY = (long) Math.ceil(Math.max(a.y, b.y) + margin);
        final long minZ = (long) Math.floor(Math.min(a.z, b.z) - margin);
        final long maxZ = (long) Math.ceil(Math.max(a.z, b.z) + margin);
        final Vector3d offset = new Vector3d();
        for (long x = minX; x <= maxX; x++) {
            for (long y = minY; y <= maxY; y++) {
                for (long z = minZ; z <= maxZ; z++) {
                    offset.set(x + 0.5 - a.x, y + 0.5 - a.y, z + 0.5 - a.z);
                    final double along = offset.dot(axis);
                    if (along < -0.5 || along > length + 0.5) continue;
                    final double rx = offset.x - axis.x * along;
                    final double ry = offset.y - axis.y * along;
                    final double rz = offset.z - axis.z * along;
                    final double radial = Math.sqrt(rx * rx + ry * ry + rz * rz);
                    final boolean insideLength = along >= -EPSILON
                        && along <= length + EPSILON;
                    final boolean keep;
                    if (shellThickness <= 0.0) {
                        keep = insideLength && (radial <= r + EPSILON
                            || radial <= 0.5);
                    } else {
                        keep = insideLength && withinShell(radial - r, rx, ry, rz,
                            radial, shellThickness);
                    }
                    if (keep) output.add(x, y, z);
                }
            }
        }
    }

    /**
     * Brush footprint centered on a cell center. A radius of zero selects
     * only the center cell; spheres use Euclidean and cubes Chebyshev distance.
     */
    public static void brush(
            final long centerCellX,
            final long centerCellY,
            final long centerCellZ,
            final int radius,
            final BrushShape shape,
            final CellVolume output) {
        final int r = Math.max(0, radius);
        final double limit = (r + 0.5) * (r + 0.5);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (shape == BrushShape.SPHERE
                            && dx * dx + dy * dy + dz * dz > limit) continue;
                    output.add(centerCellX + dx, centerCellY + dy, centerCellZ + dz);
                }
            }
        }
    }

    // =====================================================================
    //  Primitive rasterization (cell space)
    // =====================================================================

    /** Conservative, optionally thickened triangle rasterization in cell space. */
    static void triangle(
            final Vector3dc a,
            final Vector3dc b,
            final Vector3dc c,
            final double thickness,
            final CellVolume output) {
        final Vector3d ab = new Vector3d(b).sub(a);
        final Vector3d ac = new Vector3d(c).sub(a);
        final Vector3d normal = ab.cross(ac, new Vector3d());
        final double area = normal.length();
        if (area < EPSILON) {
            // Collinear points degrade to the longest edge.
            final double lab = a.distanceSquared(b);
            final double lac = a.distanceSquared(c);
            final double lbc = b.distanceSquared(c);
            if (lab >= lac && lab >= lbc) segment(a, b, thickness, output);
            else if (lac >= lbc) segment(a, c, thickness, output);
            else segment(b, c, thickness, output);
            return;
        }
        normal.div(area);
        final double reach = extraThickness(thickness)
            + 0.5 * (Math.abs(normal.x) + Math.abs(normal.y) + Math.abs(normal.z));
        final double reachSquared = reach * reach + EPSILON;

        final long minX = (long) Math.floor(Math.min(a.x(), Math.min(b.x(), c.x())) - reach);
        final long maxX = (long) Math.floor(Math.max(a.x(), Math.max(b.x(), c.x())) + reach);
        final long minY = (long) Math.floor(Math.min(a.y(), Math.min(b.y(), c.y())) - reach);
        final long maxY = (long) Math.floor(Math.max(a.y(), Math.max(b.y(), c.y())) + reach);
        final long minZ = (long) Math.floor(Math.min(a.z(), Math.min(b.z(), c.z())) - reach);
        final long maxZ = (long) Math.floor(Math.max(a.z(), Math.max(b.z(), c.z())) + reach);
        final Vector3d point = new Vector3d();
        final Vector3d closest = new Vector3d();
        for (long x = minX; x <= maxX; x++) {
            for (long y = minY; y <= maxY; y++) {
                for (long z = minZ; z <= maxZ; z++) {
                    point.set(x + 0.5, y + 0.5, z + 0.5);
                    closestPointOnTriangle(point, a, b, c, closest);
                    if (point.distanceSquared(closest) <= reachSquared) {
                        output.add(x, y, z);
                    }
                }
            }
        }
    }

    /**
     * Conservative segment rasterization: every cell the segment passes
     * through is kept, plus cells within half the thickness of the segment.
     */
    static void segment(
            final Vector3dc a,
            final Vector3dc b,
            final double thickness,
            final CellVolume output) {
        final double radius = Math.max(0.5, thickness / 2.0);
        final double reach = Math.max(radius, HALF_DIAGONAL);
        final long minX = (long) Math.floor(Math.min(a.x(), b.x()) - reach);
        final long maxX = (long) Math.floor(Math.max(a.x(), b.x()) + reach);
        final long minY = (long) Math.floor(Math.min(a.y(), b.y()) - reach);
        final long maxY = (long) Math.floor(Math.max(a.y(), b.y()) + reach);
        final long minZ = (long) Math.floor(Math.min(a.z(), b.z()) - reach);
        final long maxZ = (long) Math.floor(Math.max(a.z(), b.z()) + reach);
        final double radiusSquared = radius * radius + EPSILON;
        final boolean thick = thickness > 1.0 + EPSILON;
        final Vector3d point = new Vector3d();
        for (long x = minX; x <= maxX; x++) {
            for (long y = minY; y <= maxY; y++) {
                for (long z = minZ; z <= maxZ; z++) {
                    point.set(x + 0.5, y + 0.5, z + 0.5);
                    final boolean keep = segmentIntersectsCell(a, b, x, y, z)
                        || (thick && distanceSquaredToSegment(point, a, b) <= radiusSquared);
                    if (keep) output.add(x, y, z);
                }
            }
        }
    }

    // =====================================================================
    //  Geometry helpers
    // =====================================================================

    private static boolean withinShell(
            final double signedDistance,
            final double gx,
            final double gy,
            final double gz,
            final double gradientLength,
            final double thickness) {
        final double l1 = gradientLength < EPSILON ? 1.0
            : (Math.abs(gx) + Math.abs(gy) + Math.abs(gz)) / gradientLength;
        return Math.abs(signedDistance)
            <= extraThickness(thickness) + 0.5 * l1 + EPSILON;
    }

    /** Extra half-thickness beyond the conservative one-cell surface. */
    private static double extraThickness(final double thickness) {
        return Math.max(0.0, thickness - 1.0) / 2.0;
    }

    private static boolean isContainingCell(
            final double dx, final double dy, final double dz) {
        return Math.abs(dx) <= 0.5 && Math.abs(dy) <= 0.5 && Math.abs(dz) <= 0.5;
    }

    static boolean segmentIntersectsCell(
            final Vector3dc a,
            final Vector3dc b,
            final long cellX,
            final long cellY,
            final long cellZ) {
        double t0 = 0.0;
        double t1 = 1.0;
        final double[] origin = {a.x(), a.y(), a.z()};
        final double[] delta = {b.x() - a.x(), b.y() - a.y(), b.z() - a.z()};
        final long[] min = {cellX, cellY, cellZ};
        for (int axis = 0; axis < 3; axis++) {
            final double lo = min[axis] - EPSILON;
            final double hi = min[axis] + 1.0 + EPSILON;
            if (Math.abs(delta[axis]) < EPSILON) {
                if (origin[axis] < lo || origin[axis] > hi) return false;
                continue;
            }
            double enter = (lo - origin[axis]) / delta[axis];
            double exit = (hi - origin[axis]) / delta[axis];
            if (enter > exit) {
                final double swap = enter;
                enter = exit;
                exit = swap;
            }
            t0 = Math.max(t0, enter);
            t1 = Math.min(t1, exit);
            if (t0 > t1) return false;
        }
        return true;
    }

    static double distanceSquaredToSegment(
            final Vector3dc point,
            final Vector3dc a,
            final Vector3dc b) {
        final double abx = b.x() - a.x();
        final double aby = b.y() - a.y();
        final double abz = b.z() - a.z();
        final double lengthSquared = abx * abx + aby * aby + abz * abz;
        double t = 0.0;
        if (lengthSquared > EPSILON) {
            t = ((point.x() - a.x()) * abx + (point.y() - a.y()) * aby
                + (point.z() - a.z()) * abz) / lengthSquared;
            t = Math.clamp(t, 0.0, 1.0);
        }
        final double cx = a.x() + abx * t - point.x();
        final double cy = a.y() + aby * t - point.y();
        final double cz = a.z() + abz * t - point.z();
        return cx * cx + cy * cy + cz * cz;
    }

    /** Ericson, Real-Time Collision Detection, §5.1.5. */
    static void closestPointOnTriangle(
            final Vector3dc p,
            final Vector3dc a,
            final Vector3dc b,
            final Vector3dc c,
            final Vector3d out) {
        final double abx = b.x() - a.x(), aby = b.y() - a.y(), abz = b.z() - a.z();
        final double acx = c.x() - a.x(), acy = c.y() - a.y(), acz = c.z() - a.z();
        final double apx = p.x() - a.x(), apy = p.y() - a.y(), apz = p.z() - a.z();
        final double d1 = abx * apx + aby * apy + abz * apz;
        final double d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0.0 && d2 <= 0.0) {
            out.set(a);
            return;
        }
        final double bpx = p.x() - b.x(), bpy = p.y() - b.y(), bpz = p.z() - b.z();
        final double d3 = abx * bpx + aby * bpy + abz * bpz;
        final double d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0.0 && d4 <= d3) {
            out.set(b);
            return;
        }
        final double vc = d1 * d4 - d3 * d2;
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) {
            final double v = d1 / (d1 - d3);
            out.set(a.x() + abx * v, a.y() + aby * v, a.z() + abz * v);
            return;
        }
        final double cpx = p.x() - c.x(), cpy = p.y() - c.y(), cpz = p.z() - c.z();
        final double d5 = abx * cpx + aby * cpy + abz * cpz;
        final double d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0.0 && d5 <= d6) {
            out.set(c);
            return;
        }
        final double vb = d5 * d2 - d1 * d6;
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) {
            final double w = d2 / (d2 - d6);
            out.set(a.x() + acx * w, a.y() + acy * w, a.z() + acz * w);
            return;
        }
        final double va = d3 * d6 - d5 * d4;
        if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) {
            final double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            out.set(b.x() + (c.x() - b.x()) * w,
                b.y() + (c.y() - b.y()) * w,
                b.z() + (c.z() - b.z()) * w);
            return;
        }
        final double denominator = 1.0 / (va + vb + vc);
        final double v = vb * denominator;
        final double w = vc * denominator;
        out.set(a.x() + abx * v + acx * w,
            a.y() + aby * v + acy * w,
            a.z() + abz * v + acz * w);
    }

    static Vector3d evaluateBezierSurface(
            final List<Vector3d> net,
            final int rows,
            final int columns,
            final double u,
            final double v) {
        final double[] bu = bernstein(rows - 1, u);
        final double[] bv = bernstein(columns - 1, v);
        final Vector3d result = new Vector3d();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                final double weight = bu[row] * bv[column];
                final Vector3d point = net.get(row * columns + column);
                result.add(point.x * weight, point.y * weight, point.z * weight);
            }
        }
        return result;
    }

    private static double[] bernstein(final int degree, final double t) {
        final double[] weights = new double[degree + 1];
        for (int index = 0; index <= degree; index++) {
            weights[index] = binomial(degree, index)
                * Math.pow(t, index) * Math.pow(1.0 - t, degree - index);
        }
        return weights;
    }

    private static double binomial(final int n, final int k) {
        double result = 1.0;
        for (int index = 1; index <= k; index++) {
            result = result * (n - k + index) / index;
        }
        return result;
    }

    static Vector3d catmullRom(
            final Vector3dc p0,
            final Vector3dc p1,
            final Vector3dc p2,
            final Vector3dc p3,
            final double t) {
        final double t2 = t * t;
        final double t3 = t2 * t;
        final double w0 = -0.5 * t3 + t2 - 0.5 * t;
        final double w1 = 1.5 * t3 - 2.5 * t2 + 1.0;
        final double w2 = -1.5 * t3 + 2.0 * t2 + 0.5 * t;
        final double w3 = 0.5 * t3 - 0.5 * t2;
        return new Vector3d(
            p0.x() * w0 + p1.x() * w1 + p2.x() * w2 + p3.x() * w3,
            p0.y() * w0 + p1.y() * w1 + p2.y() * w2 + p3.y() * w3,
            p0.z() * w0 + p1.z() * w1 + p2.z() * w2 + p3.z() * w3);
    }

    private static double maxPolylineLength(
            final List<Vector3d> net,
            final int rows,
            final int columns,
            final boolean alongRows) {
        double maximum = 0.0;
        final int lines = alongRows ? columns : rows;
        final int length = alongRows ? rows : columns;
        for (int line = 0; line < lines; line++) {
            double total = 0.0;
            for (int index = 1; index < length; index++) {
                final Vector3d previous = alongRows
                    ? net.get((index - 1) * columns + line)
                    : net.get(line * columns + index - 1);
                final Vector3d current = alongRows
                    ? net.get(index * columns + line)
                    : net.get(line * columns + index);
                total += previous.distance(current);
            }
            maximum = Math.max(maximum, total);
        }
        return maximum;
    }

    private static int segmentsFor(final double lengthInCells) {
        return Math.max(1, Math.min(MAX_SURFACE_SEGMENTS,
            (int) Math.ceil(lengthInCells / 1.5)));
    }

    private static List<Vector3d> toCellSpace(
            final List<? extends Vector3dc> points,
            final int grid) {
        final List<Vector3d> result = new ArrayList<>(points.size());
        for (final Vector3dc point : points) {
            result.add(new Vector3d(point).mul(grid));
        }
        return result;
    }

    private static void requireAtLeast(
            final List<? extends Vector3dc> points,
            final int minimum) {
        if (points == null || points.size() < minimum) {
            throw new IllegalArgumentException(
                "at least " + minimum + " points are required");
        }
    }
}
