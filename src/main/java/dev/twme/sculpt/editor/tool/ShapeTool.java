package dev.twme.sculpt.editor.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.joml.Vector3d;
import org.joml.Vector3f;

import dev.twme.sculpt.building.BlockCellEdit;
import dev.twme.sculpt.building.BuildLimits;
import dev.twme.sculpt.building.CellEdits;
import dev.twme.sculpt.building.CellVolume;
import dev.twme.sculpt.building.ShapeRasterizer;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.editor.CellTarget;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.ui.EditorDialogs;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Multi-angle planes, curved surfaces, curves, spheres, and cylinders from
 * control points. Left click adds a point in front of the targeted face, or
 * grabs a point under the cursor so it follows the view; left click again
 * drops it. The shape is previewed live and applied with right click.
 */
public final class ShapeTool implements Tool {

    /** Shapes and the control points each one uses. */
    public enum Type {
        PLANE(3, MAX_POINTS),
        SURFACE(4, MAX_POINTS),
        CURVE(2, MAX_POINTS),
        SPHERE(2, 2),
        CYLINDER(3, 3);

        final int minPoints;
        final int maxPoints;

        Type(final int minPoints, final int maxPoints) {
            this.minPoints = minPoints;
            this.maxPoints = maxPoints;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final int MAX_POINTS = 16;
    private static final String PREFIX = "shape.";
    private static final double HANDLE_HALF = 0.09;
    private static final double PICK_RADIUS = 0.25;
    private static final int SURFACE_LINES = 5;
    private static final int SURFACE_SEGMENTS = 12;

    private final List<Vector3d> points = new ArrayList<>();
    private Type type = Type.PLANE;
    private int thickness = 1;
    private boolean hollow;
    private boolean carve;
    private int rows;
    private int dragging = -1;
    private double dragDistance;
    private Type previewedType;

    @Override
    public ToolId id() {
        return ToolId.SHAPE;
    }

    public Type type() {
        return type;
    }

    public int thickness() {
        return thickness;
    }

    public boolean hollow() {
        return hollow;
    }

    public boolean carve() {
        return carve;
    }

    public int rows() {
        return rows;
    }

    public void configure(final EditorSession session, final Type newType, final int newThickness,
                          final boolean newHollow, final boolean newCarve, final int newRows) {
        this.type = newType;
        this.thickness = Math.clamp(newThickness, 1, limits(session).maxThickness());
        this.hollow = newHollow;
        this.carve = newCarve;
        this.rows = Math.max(0, newRows);
    }

    // =====================================================================
    //  Input
    // =====================================================================

    @Override
    public void primary(final EditorSession session) {
        if (dragging >= 0) {
            dragging = -1;
            return;
        }
        final int hovered = hovered(session);
        if (hovered >= 0) {
            dragging = hovered;
            dragDistance = eye(session).distance(points.get(hovered));
            return;
        }
        final CellTarget target = session.target();
        if (target == null) return;
        if (points.size() >= MAX_POINTS) {
            session.flash("building.points.limit", MAX_POINTS);
            return;
        }
        points.add(target.center(true));
        session.flash("editor.shape.point_added", points.size());
    }

    @Override
    public void secondary(final EditorSession session) {
        final String problem = validate();
        if (problem != null) {
            session.flash(problem, points.size());
            return;
        }
        CellMaterial material = null;
        if (!carve) {
            material = session.usableMaterial();
            if (material == null) return;
        }
        final CellMaterial chosen = material;
        final int grid = session.grid();
        final BuildLimits limits = limits(session);
        final Consumer<CellVolume> rasterizer = rasterizer();
        session.commitAsync(type.id(), () -> {
            final CellVolume volume = new CellVolume(grid, limits.maxBlocks(), limits.maxCells());
            rasterizer.accept(volume);
            return CellEdits.of(volume,
                carve ? BlockCellEdit.Operation.CARVE : BlockCellEdit.Operation.ADD, chosen);
        }, carve ? Colors.REMOVE : Colors.ADD);
    }

    @Override
    public void adjust(final EditorSession session, final int delta) {
        if (dragging >= 0) {
            dragDistance = Math.clamp(dragDistance + delta * 0.5, 1.0, session.service().config().reach() * 2);
            return;
        }
        thickness = Math.clamp(thickness + delta, 1, limits(session).maxThickness());
    }

    @Override
    public boolean cancel(final EditorSession session) {
        if (dragging >= 0) {
            points.remove(dragging);
            dragging = -1;
            session.flash("editor.shape.point_removed", points.size());
            return true;
        }
        if (points.isEmpty()) return false;
        points.clear();
        session.flash("building.points.cleared");
        return true;
    }

    @Override
    public void openSettings(final EditorSession session) {
        EditorDialogs.shape(session, this);
    }

    @Override
    public void deactivate(final EditorSession session) {
        dragging = -1;
        session.scene().removePrefix(PREFIX);
    }

    // =====================================================================
    //  Preview
    // =====================================================================

    @Override
    public void preview(final EditorSession session) {
        if (dragging >= 0 && dragging < points.size()) {
            points.set(dragging, snap(session, dragTarget(session)));
        }
        final int hovered = dragging >= 0 ? dragging : hovered(session);
        for (int index = 0; index < MAX_POINTS; index++) {
            final String key = PREFIX + "handle." + index;
            if (index >= points.size()) {
                session.scene().remove(key);
                continue;
            }
            final Vector3d point = points.get(index);
            final int color = index == dragging ? Colors.ADD
                : index == hovered ? Colors.CURSOR : Colors.SELECT;
            session.scene().outline(key, handleMin(point), handleMax(point), color);
        }
        if (points.isEmpty()) {
            Previews.cell(session, PREFIX + "cursor", true, Colors.CURSOR);
        } else {
            session.scene().remove(PREFIX + "cursor");
        }
        session.scene().polyline(PREFIX + "net", floats(points), false, 0.01f,
            Colors.withAlpha(Colors.CURSOR, 0x60));
        previewShape(session);
    }

    private void previewShape(final EditorSession session) {
        final int color = carve ? Colors.REMOVE : Colors.ADD;
        if (previewedType != type) {
            session.scene().removePrefix(PREFIX + "preview.");
            previewedType = type;
        }
        if (validate() != null) {
            session.scene().removePrefix(PREFIX + "preview.");
            return;
        }
        switch (type) {
            case PLANE -> {
                final List<Vector3f[]> triangles = new ArrayList<>();
                for (int index = 1; index + 1 < points.size(); index++) {
                    triangles.add(new Vector3f[]{
                        f(points.getFirst()), f(points.get(index)), f(points.get(index + 1))});
                }
                session.scene().triangles(PREFIX + "preview.plane", triangles, Colors.face(color));
            }
            case SURFACE -> {
                final int netRows = surfaceRows(points.size(), rows);
                for (int line = 0; line < SURFACE_LINES; line++) {
                    final double t = (double) line / (SURFACE_LINES - 1);
                    session.scene().polyline(PREFIX + "preview.u" + line,
                        surfaceLine(netRows, t, true), false, 0.02f, color);
                    session.scene().polyline(PREFIX + "preview.v" + line,
                        surfaceLine(netRows, t, false), false, 0.02f, color);
                }
            }
            case CURVE -> session.scene().polyline(PREFIX + "preview.curve",
                floats(ShapeRasterizer.curvePoints(points, 8)), false, 0.03f, color);
            case SPHERE -> {
                final double radius = points.get(0).distance(points.get(1));
                for (int axis = 0; axis < 3; axis++) {
                    session.scene().polyline(PREFIX + "preview.ring" + axis,
                        Previews.ring(points.get(0), radius, axis), true, 0.02f, color);
                }
            }
            case CYLINDER -> previewCylinder(session, color);
        }
    }

    private void previewCylinder(final EditorSession session, final int color) {
        final Vector3d start = points.get(0);
        final Vector3d end = points.get(1);
        final double radius = distanceToAxis(points.get(2), start, end);
        final Vector3d axis = new Vector3d(end).sub(start);
        if (axis.lengthSquared() < 1e-9) return;
        axis.normalize();
        final Vector3d u = new Vector3d(Math.abs(axis.y) < 0.99 ? 0 : 1, Math.abs(axis.y) < 0.99 ? 1 : 0, 0)
            .cross(axis).normalize();
        final Vector3d v = new Vector3d(axis).cross(u).normalize();
        session.scene().polyline(PREFIX + "preview.cap0", circle(start, u, v, radius), true, 0.02f, color);
        session.scene().polyline(PREFIX + "preview.cap1", circle(end, u, v, radius), true, 0.02f, color);
        for (int side = 0; side < 4; side++) {
            final double angle = Math.PI / 2 * side;
            final Vector3d offset = new Vector3d(u).mul(Math.cos(angle) * radius)
                .add(new Vector3d(v).mul(Math.sin(angle) * radius));
            session.scene().polyline(PREFIX + "preview.side" + side, List.of(
                f(new Vector3d(start).add(offset)), f(new Vector3d(end).add(offset))), false, 0.02f, color);
        }
    }

    private List<Vector3f> surfaceLine(final int netRows, final double fixed, final boolean alongU) {
        final List<Vector3f> line = new ArrayList<>(SURFACE_SEGMENTS + 1);
        for (int step = 0; step <= SURFACE_SEGMENTS; step++) {
            final double t = (double) step / SURFACE_SEGMENTS;
            line.add(f(alongU
                ? ShapeRasterizer.bezierPoint(points, netRows, t, fixed)
                : ShapeRasterizer.bezierPoint(points, netRows, fixed, t)));
        }
        return line;
    }

    // =====================================================================
    //  Geometry
    // =====================================================================

    /** The translation key describing why the points cannot form the shape, or {@code null}. */
    String validate() {
        if (points.size() < type.minPoints || points.size() > type.maxPoints) {
            return "building.shape." + type.id() + ".points";
        }
        if (type == Type.SURFACE && surfaceRows(points.size(), rows) < 0) {
            return "building.shape.surface.grid";
        }
        return null;
    }

    Consumer<CellVolume> rasterizer() {
        final List<Vector3d> copy = new ArrayList<>();
        for (final Vector3d point : points) copy.add(new Vector3d(point));
        final double shell = hollow ? thickness : 0.0;
        final double width = thickness;
        final int netRows = type == Type.SURFACE ? surfaceRows(copy.size(), rows) : 0;
        return switch (type) {
            case PLANE -> volume -> ShapeRasterizer.polygon(copy, width, volume);
            case SURFACE -> volume -> ShapeRasterizer.bezierSurface(copy, netRows, width, volume);
            case CURVE -> volume -> ShapeRasterizer.curve(copy, width, volume);
            case SPHERE -> volume -> ShapeRasterizer.sphere(copy.get(0),
                copy.get(0).distance(copy.get(1)), shell, volume);
            case CYLINDER -> volume -> ShapeRasterizer.cylinder(copy.get(0), copy.get(1),
                distanceToAxis(copy.get(2), copy.get(0), copy.get(1)), shell, volume);
        };
    }

    /**
     * Rows of a surface control net. An explicit value must divide the point
     * count; otherwise square nets are preferred, then two rows.
     *
     * @return the row count, or {@code -1} when no valid grid exists
     */
    static int surfaceRows(final int count, final int requested) {
        if (requested > 0) {
            return count % requested == 0 && count / requested >= 2 && requested >= 2 ? requested : -1;
        }
        final int root = (int) Math.round(Math.sqrt(count));
        if (root >= 2 && root * root == count) return root;
        return count % 2 == 0 && count >= 4 ? 2 : -1;
    }

    static double distanceToAxis(final Vector3d point, final Vector3d start, final Vector3d end) {
        final Vector3d axis = new Vector3d(end).sub(start);
        final double length = axis.length();
        if (length < 1e-9) return point.distance(start);
        axis.div(length);
        final Vector3d offset = new Vector3d(point).sub(start);
        final double along = offset.dot(axis);
        return offset.sub(axis.mul(along)).length();
    }

    /** Index of the handle closest to the view ray, within the pick radius. */
    private int hovered(final EditorSession session) {
        final Vector3d eye = eye(session);
        final Vector3d direction = direction(session);
        final double reach = session.service().config().reach() + 2;
        int best = -1;
        double bestDistance = PICK_RADIUS;
        for (int index = 0; index < points.size(); index++) {
            final Vector3d offset = new Vector3d(points.get(index)).sub(eye);
            final double along = offset.dot(direction);
            if (along < 0 || along > reach) continue;
            final double distance = offset.sub(new Vector3d(direction).mul(along)).length();
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    private Vector3d dragTarget(final EditorSession session) {
        return eye(session).add(direction(session).mul(dragDistance));
    }

    /** Snap to the center of the cell containing the point at the current resolution. */
    private static Vector3d snap(final EditorSession session, final Vector3d point) {
        final int grid = session.grid();
        return new Vector3d(
            (Math.floor(point.x * grid) + 0.5) / grid,
            (Math.floor(point.y * grid) + 0.5) / grid,
            (Math.floor(point.z * grid) + 0.5) / grid);
    }

    private static Vector3d eye(final EditorSession session) {
        final Location eye = session.player().getEyeLocation();
        return new Vector3d(eye.getX(), eye.getY(), eye.getZ());
    }

    private static Vector3d direction(final EditorSession session) {
        final var direction = session.player().getEyeLocation().getDirection();
        return new Vector3d(direction.getX(), direction.getY(), direction.getZ()).normalize();
    }

    private static List<Vector3f> circle(final Vector3d center, final Vector3d u, final Vector3d v,
                                         final double radius) {
        final List<Vector3f> points = new ArrayList<>(24);
        for (int index = 0; index < 24; index++) {
            final double angle = Math.PI * 2 * index / 24;
            points.add(f(new Vector3d(center)
                .add(new Vector3d(u).mul(Math.cos(angle) * radius))
                .add(new Vector3d(v).mul(Math.sin(angle) * radius))));
        }
        return points;
    }

    private static Vector3f handleMin(final Vector3d point) {
        return new Vector3f((float) (point.x - HANDLE_HALF), (float) (point.y - HANDLE_HALF),
            (float) (point.z - HANDLE_HALF));
    }

    private static Vector3f handleMax(final Vector3d point) {
        return new Vector3f((float) (point.x + HANDLE_HALF), (float) (point.y + HANDLE_HALF),
            (float) (point.z + HANDLE_HALF));
    }

    private static Vector3f f(final Vector3d point) {
        return new Vector3f((float) point.x, (float) point.y, (float) point.z);
    }

    private static List<Vector3f> floats(final List<Vector3d> input) {
        final List<Vector3f> output = new ArrayList<>(input.size());
        for (final Vector3d point : input) output.add(f(point));
        return output;
    }

    private static BuildLimits limits(final EditorSession session) {
        return session.service().engine().limits();
    }

    @Override
    public String status(final EditorSession session) {
        return MessageUtil.getTranslated(session.player(), "editor.status.shape",
            MessageUtil.getTranslated(session.player(), "editor.shape_type." + type.id()),
            points.size(), thickness);
    }
}
