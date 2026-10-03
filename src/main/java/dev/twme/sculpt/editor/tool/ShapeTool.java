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
 * Multi-angle planes, lofted surfaces, curves, spheres, and cylinders from
 * control points. Left click adds a point in front of the targeted face, or
 * grabs a point under the cursor so it follows the view; left click again
 * drops it. The shape is previewed live and applied with right click.
 *
 * <p>Every shape is described by one or more control lines. A plane, curve,
 * sphere, or cylinder uses a single line. A surface uses one line per
 * left-click group: {@code Shift} + left click starts the next line, and the
 * strips between consecutive lines are swept into one continuous surface, so
 * line A, then B, then C produces a surface from A through B to C.</p>
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
    /** Points one surface line needs before the next line may start. */
    static final int MIN_LINE_POINTS = 2;
    private static final String PREFIX = "shape.";
    private static final double HANDLE_HALF = 0.09;
    private static final double PICK_RADIUS = 0.25;
    private static final int SURFACE_LINES = 5;

    private final List<List<Vector3d>> lines = new ArrayList<>();
    private Type type = Type.PLANE;
    private int thickness = 1;
    private boolean hollow;
    private boolean carve;
    private int draggingLine = -1;
    private int draggingIndex = -1;
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

    /** The control lines drawn so far; the last one is still being drawn. */
    public List<List<Vector3d>> lines() {
        return lines;
    }

    public void configure(final Type newType, final int newThickness, final int maxThickness,
                          final boolean newHollow, final boolean newCarve) {
        this.type = newType;
        this.thickness = Math.clamp(newThickness, 1, Math.max(1, maxThickness));
        this.hollow = newHollow;
        this.carve = newCarve;
        if (newType != Type.SURFACE && lines.size() > 1) {
            final List<Vector3d> merged = new ArrayList<>(lines.getFirst());
            lines.clear();
            lines.add(merged);
        }
    }

    // =====================================================================
    //  Input
    // =====================================================================

    @Override
    public void primary(final EditorSession session) {
        if (draggingLine >= 0) {
            draggingLine = -1;
            draggingIndex = -1;
            return;
        }
        final int[] hovered = hovered(session);
        if (hovered != null) {
            draggingLine = hovered[0];
            draggingIndex = hovered[1];
            dragDistance = eye(session).distance(lines.get(hovered[0]).get(hovered[1]));
            return;
        }
        final CellTarget target = session.target();
        if (target == null) return;
        if (type == Type.SURFACE && session.player().isSneaking()) {
            startLine(session);
            return;
        }
        if (totalPoints() >= MAX_POINTS) {
            session.flash("building.points.limit", MAX_POINTS);
            return;
        }
        if (lines.isEmpty()) lines.add(new ArrayList<>());
        lines.getLast().add(target.center(true));
        session.flash("editor.shape.point_added", totalPoints());
    }

    /** Begin the next surface line, once the current one has enough points. */
    private void startLine(final EditorSession session) {
        if (!lines.isEmpty() && lines.getLast().size() < MIN_LINE_POINTS) {
            session.flash("editor.shape.line_short", MIN_LINE_POINTS);
            return;
        }
        lines.add(new ArrayList<>());
        session.flash("editor.shape.line_started", lines.size());
    }

    @Override
    public void secondary(final EditorSession session) {
        final String problem = validate();
        if (problem != null) {
            session.flash(problem, totalPoints());
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
        if (draggingLine >= 0) {
            dragDistance = Math.clamp(dragDistance + delta * 0.5, 1.0, session.service().config().reach() * 2);
            return;
        }
        thickness = Math.clamp(thickness + delta, 1, limits(session).maxThickness());
    }

    /** {@code Q} removes the point drawn last, then the lines before it. */
    @Override
    public boolean cancel(final EditorSession session) {
        if (draggingLine >= 0) {
            removePoint(draggingLine, draggingIndex);
            draggingLine = -1;
            draggingIndex = -1;
            session.flash("editor.shape.point_removed", totalPoints());
            return true;
        }
        if (lines.isEmpty()) return false;
        final int last = lines.size() - 1;
        removePoint(last, lines.get(last).size() - 1);
        if (lines.get(last).isEmpty() && lines.size() > 1) {
            lines.remove(last);
            session.flash("editor.shape.line_removed", lines.size());
            return true;
        }
        if (lines.size() == 1 && lines.getFirst().isEmpty()) {
            lines.clear();
            session.flash("building.points.cleared");
            return true;
        }
        session.flash("editor.shape.point_removed", totalPoints());
        return true;
    }

    private void removePoint(final int line, final int index) {
        if (line < 0 || line >= lines.size()) return;
        final List<Vector3d> points = lines.get(line);
        if (index < 0 || index >= points.size()) return;
        points.remove(index);
    }

    @Override
    public void openSettings(final EditorSession session) {
        EditorDialogs.shape(session, this);
    }

    @Override
    public void deactivate(final EditorSession session) {
        draggingLine = -1;
        draggingIndex = -1;
        session.scene().removePrefix(PREFIX);
    }

    // =====================================================================
    //  Preview
    // =====================================================================

    @Override
    public void preview(final EditorSession session) {
        if (draggingLine >= 0 && draggingLine < lines.size()
                && draggingIndex < lines.get(draggingLine).size()) {
            lines.get(draggingLine).set(draggingIndex, snap(session, dragTarget(session)));
        }
        final int[] hovered = draggingLine >= 0
            ? new int[]{draggingLine, draggingIndex} : hovered(session);
        int flat = 0;
        for (int line = 0; line < lines.size(); line++) {
            final List<Vector3d> points = lines.get(line);
            for (int index = 0; index < points.size(); index++, flat++) {
                final String key = PREFIX + "handle." + flat;
                final int color = line == draggingLine && index == draggingIndex ? Colors.ADD
                    : hovered != null && hovered[0] == line && hovered[1] == index ? Colors.CURSOR
                    : Colors.SELECT;
                session.scene().outline(key, handleMin(points.get(index)),
                    handleMax(points.get(index)), color);
            }
        }
        for (int index = totalPoints(); index < MAX_POINTS; index++) {
            session.scene().remove(PREFIX + "handle." + index);
        }
        for (int line = 0; line < lines.size(); line++) {
            session.scene().polyline(PREFIX + "line" + line,
                floats(lines.get(line)), false, 0.01f, Colors.withAlpha(Colors.CURSOR, 0x60));
        }
        for (int line = lines.size(); line < MAX_POINTS; line++) {
            session.scene().remove(PREFIX + "line" + line);
        }
        if (lines.isEmpty()) {
            Previews.cell(session, PREFIX + "cursor", true, Colors.CURSOR);
        } else {
            session.scene().remove(PREFIX + "cursor");
        }
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
                final List<Vector3d> points = lines.getFirst();
                for (int index = 1; index + 1 < points.size(); index++) {
                    triangles.add(new Vector3f[]{
                        f(points.getFirst()), f(points.get(index)), f(points.get(index + 1))});
                }
                session.scene().triangles(PREFIX + "preview.plane", triangles, Colors.face(color));
            }
            case SURFACE -> previewSurface(session, color);
            case CURVE -> session.scene().polyline(PREFIX + "preview.curve",
                floats(ShapeRasterizer.curvePoints(lines.getFirst(), 8)), false, 0.03f, color);
            case SPHERE -> {
                final List<Vector3d> points = lines.getFirst();
                final double radius = points.get(0).distance(points.get(1));
                for (int axis = 0; axis < 3; axis++) {
                    session.scene().polyline(PREFIX + "preview.ring" + axis,
                        Previews.ring(points.get(0), radius, axis), true, 0.02f, color);
                }
            }
            case CYLINDER -> previewCylinder(session, color);
        }
    }

    /**
     * Draw the same net {@link ShapeRasterizer#loft} fills: one line along
     * each sample row, and cross lines joining the matching points of the
     * consecutive control lines.
     */
    private void previewSurface(final EditorSession session, final int color) {
        final List<List<Vector3d>> net = ShapeRasterizer.loftNet(lines, session.grid());
        final int rows = net.getFirst().size();
        final int strips = net.size();
        for (int line = 0; line < SURFACE_LINES; line++) {
            final int index = Math.min(strips - 1,
                (int) Math.round((double) line / (SURFACE_LINES - 1) * (strips - 1)));
            session.scene().polyline(PREFIX + "preview.u" + line,
                floats(net.get(index)), false, 0.02f, color);
        }
        for (int line = 0; line < SURFACE_LINES; line++) {
            final int index = Math.min(rows - 1,
                (int) Math.round((double) line / (SURFACE_LINES - 1) * (rows - 1)));
            final List<Vector3d> column = new ArrayList<>(strips);
            for (final List<Vector3d> sample : net) column.add(sample.get(index));
            session.scene().polyline(PREFIX + "preview.v" + line, floats(column), false, 0.02f, color);
        }
    }

    private void previewCylinder(final EditorSession session, final int color) {
        final List<Vector3d> points = lines.getFirst();
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

    // =====================================================================
    //  Geometry
    // =====================================================================

    /** The translation key describing why the points cannot form the shape, or {@code null}. */
    String validate() {
        if (type == Type.SURFACE) {
            if (lines.size() < 2) return "building.shape.surface.lines";
            for (final List<Vector3d> line : lines) {
                if (line.size() < MIN_LINE_POINTS) return "building.shape.surface.line_points";
            }
            return null;
        }
        final int count = totalPoints();
        if (count < type.minPoints || count > type.maxPoints) {
            return "building.shape." + type.id() + ".points";
        }
        return null;
    }

    Consumer<CellVolume> rasterizer() {
        final double shell = hollow ? thickness : 0.0;
        final double width = thickness;
        if (type == Type.SURFACE) {
            final List<List<Vector3d>> copy = copyLines();
            return volume -> ShapeRasterizer.loft(copy, width, volume);
        }
        final List<Vector3d> copy = new ArrayList<>(lines.getFirst());
        return switch (type) {
            case PLANE -> volume -> ShapeRasterizer.polygon(copy, width, volume);
            case CURVE -> volume -> ShapeRasterizer.curve(copy, width, volume);
            case SPHERE -> volume -> ShapeRasterizer.sphere(copy.get(0),
                copy.get(0).distance(copy.get(1)), shell, volume);
            case CYLINDER -> volume -> ShapeRasterizer.cylinder(copy.get(0), copy.get(1),
                distanceToAxis(copy.get(2), copy.get(0), copy.get(1)), shell, volume);
            case SURFACE -> throw new IllegalStateException("handled above");
        };
    }

    private List<List<Vector3d>> copyLines() {
        final List<List<Vector3d>> copy = new ArrayList<>(lines.size());
        for (final List<Vector3d> line : lines) {
            final List<Vector3d> points = new ArrayList<>(line.size());
            for (final Vector3d point : line) points.add(new Vector3d(point));
            copy.add(points);
        }
        return copy;
    }

    private int totalPoints() {
        int total = 0;
        for (final List<Vector3d> line : lines) total += line.size();
        return total;
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

    /** Line and index of the handle closest to the view ray, within the pick radius. */
    private int[] hovered(final EditorSession session) {
        final Vector3d eye = eye(session);
        final Vector3d direction = direction(session);
        final double reach = session.service().config().reach() + 2;
        int[] best = null;
        double bestDistance = PICK_RADIUS;
        for (int line = 0; line < lines.size(); line++) {
            final List<Vector3d> points = lines.get(line);
            for (int index = 0; index < points.size(); index++) {
                final Vector3d offset = new Vector3d(points.get(index)).sub(eye);
                final double along = offset.dot(direction);
                if (along < 0 || along > reach) continue;
                final double distance = offset.sub(new Vector3d(direction).mul(along)).length();
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new int[]{line, index};
                }
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
        final String shape = MessageUtil.getTranslated(session.player(),
            "editor.shape_type." + type.id());
        if (type == Type.SURFACE) {
            return MessageUtil.getTranslated(session.player(), "editor.status.surface",
                shape, lines.size(), totalPoints(), thickness);
        }
        return MessageUtil.getTranslated(session.player(), "editor.status.shape",
            shape, totalPoints(), thickness);
    }
}
