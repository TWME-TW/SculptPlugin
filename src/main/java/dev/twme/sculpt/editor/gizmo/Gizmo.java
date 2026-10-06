package dev.twme.sculpt.editor.gizmo;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;
import org.joml.Vector3f;

import dev.twme.sculpt.editor.VoxelBox;
import dev.twme.sculpt.editor.VoxelRotation;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.preview.PreviewScene;

/**
 * The transform gizmo: draggable handles drawn at the center of a voxel
 * selection.
 *
 * <p>Picking and dragging both work from the player's view ray. A handle is
 * picked by the nearest hit along that ray. While a handle is held, movement
 * of the ray is projected onto that handle's own axis or plane, so a drag only
 * ever changes the one thing the handle controls.</p>
 *
 * <p>The gizmo owns no world state: it only reports the rotation and offset a
 * drag has produced, and the caller decides when to apply them.</p>
 */
public final class Gizmo {

    // ---- handle geometry, in blocks relative to the pivot ----
    private static final double AXIS_INNER = 0.22;
    private static final double AXIS_OUTER = 1.35;
    private static final double AXIS_RADIUS = 0.06;
    private static final double TIP_HALF = 0.09;
    private static final double PLANE_INNER = 0.30;
    private static final double PLANE_OUTER = 0.78;
    private static final double RING_RADIUS = 1.75;
    private static final double RING_THICKNESS = 0.18;
    /** A full circle: the ring is grabbable anywhere around the pivot. */
    private static final double RING_SPAN = Math.PI * 2;
    private static final double MIRROR_RADIUS = 1.50;
    private static final double MIRROR_HALF = 0.12;
    /** Handles are picked a little more generously than they are drawn. */
    private static final double PICK_SLACK = 0.06;
    /** How far the player may reach a handle, in blocks. */
    private static final double MAX_REACH = 12.0;

    /** Moves always land on whole voxels, so a drag can never split a cell. */
    public static final double SNAP_VOXELS = 1.0;
    /**
     * Rotation snap steps in degrees, coarsest first. {@code 0} means free
     * rotation. {@code Shift} + scroll cycles through them.
     */
    public static final double[] ANGLE_STEPS = {90, 45, 15, 5, 1, 0};
    private static final int RING_SEGMENTS = 48;

    private final String prefix;
    private GizmoHandle hovered;
    private GizmoHandle dragging;
    private Vector3d dragStart;
    private Vector3d dragDirection;
    private Vector3d dragPlaneNormal;
    /**
     * The pivot the active drag measures against, captured when the drag
     * began. The drawn pivot follows the pending move, so measuring against
     * the live one would feed each tick's offset back into the next.
     */
    private Vector3d dragPivot;
    private double dragStartAngle;
    private int angleStep = 2;

    // The result of the current drag.
    private double angle;
    private long offsetX;
    private long offsetY;
    private long offsetZ;
    private boolean mirrorX;
    private boolean mirrorZ;

    public Gizmo(final String prefix) {
        this.prefix = prefix;
    }

    // =====================================================================
    //  State
    // =====================================================================

    /** The handle under the cursor, or {@code null}. */
    public GizmoHandle hovered() {
        return hovered;
    }

    public boolean isDragging() {
        return dragging != null;
    }

    public GizmoHandle dragging() {
        return dragging;
    }

    /** The current rotation snap step in degrees; {@code 0} means free rotation. */
    public double angleStep() {
        return ANGLE_STEPS[Math.floorMod(angleStep, ANGLE_STEPS.length)];
    }

    /** Cycle the rotation snap step; {@code delta} of +1 is one step finer. */
    public void adjustAngleStep(final int delta) {
        angleStep = Math.floorMod(angleStep + delta, ANGLE_STEPS.length);
    }

    public double angle() {
        return angle;
    }

    public long offsetX() {
        return offsetX;
    }

    public long offsetY() {
        return offsetY;
    }

    public long offsetZ() {
        return offsetZ;
    }

    public boolean mirrorX() {
        return mirrorX;
    }

    public boolean mirrorZ() {
        return mirrorZ;
    }

    /** Whether a drag has produced any change worth applying. */
    public boolean isChanged() {
        return angle != 0 || offsetX != 0 || offsetY != 0 || offsetZ != 0 || mirrorX || mirrorZ;
    }

    /** The rotation the current drag describes, around {@code source}'s center. */
    public VoxelRotation rotation(final VoxelBox source) {
        return VoxelRotation.around(source, angle, mirrorX, mirrorZ);
    }

    /**
     * Where the handles should be drawn: the selection center carried by the
     * pending move, so the gizmo travels with the ghost instead of sitting on
     * the original position.
     */
    public Vector3d drawnPivot(final VoxelBox source) {
        final Vector3d center = source.center();
        return center.add(
            offsetX / (double) VoxelBox.VOXELS_PER_BLOCK,
            offsetY / (double) VoxelBox.VOXELS_PER_BLOCK,
            offsetZ / (double) VoxelBox.VOXELS_PER_BLOCK);
    }

    /** Whether a rotation is being dragged right now. */
    public boolean isRotating() {
        return dragging != null && dragging.kind() == GizmoHandle.Kind.ROTATE;
    }

    /** The pending rotation in degrees, normalized to {@code (-180, 180]}. */
    public double degrees() {
        double degrees = Math.toDegrees(angle) % 360.0;
        if (degrees > 180.0) degrees -= 360.0;
        if (degrees <= -180.0) degrees += 360.0;
        return degrees;
    }

    /** Drop every pending change and any active drag. */
    public void reset() {
        dragging = null;
        hovered = null;
        dragPivot = null;
        angle = 0;
        offsetX = 0;
        offsetY = 0;
        offsetZ = 0;
        mirrorX = false;
        mirrorZ = false;
    }

    /** Stop dragging but keep the pending change. */
    public void release() {
        dragging = null;
        dragPivot = null;
    }

    /** Forget the hovered handle, for example when the selection goes away. */
    public void clearHover() {
        hovered = null;
        dragging = null;
    }

    // =====================================================================
    //  Picking and dragging
    // =====================================================================

    /**
     * Recompute the hovered handle and advance an active drag from the
     * player's current view. Must run every tick while the gizmo is shown.
     */
    public void update(final Vector3d pivot, final Vector3d eye, final Vector3d direction) {
        if (dragging != null) {
            drag(pivot, eye, direction);
            return;
        }
        hovered = pick(pivot, eye, direction);
    }

    /** Begin dragging the hovered handle, or return {@code false}. */
    public boolean beginDrag(final Vector3d pivot, final Vector3d eye, final Vector3d direction) {
        if (hovered == null) return false;
        final GizmoHandle handle = hovered;
        dragPivot = new Vector3d(pivot);
        switch (handle.kind()) {
            case MOVE, MIRROR -> {
                dragDirection = handle.direction();
                dragPlaneNormal = dragPlane(direction);
                dragStart = GizmoMath.rayPlane(eye, direction, pivot, dragPlaneNormal);
            }
            case MOVE_PLANE -> {
                dragPlaneNormal = handle.direction();
                dragStart = GizmoMath.rayPlane(eye, direction, pivot, dragPlaneNormal);
            }
            case ROTATE -> {
                dragPlaneNormal = handle.direction();
                dragStart = GizmoMath.rayPlane(eye, direction, pivot, dragPlaneNormal);
                dragStartAngle = angleOf(dragStart, pivot, handle);
            }
            default -> dragStart = null;
        }
        dragging = handle;
        return true;
    }

    /** Toggle a mirror handle; mirrors apply immediately, not by dragging. */
    public void toggleMirror() {
        if (dragging == GizmoHandle.MIRROR_X) mirrorX = !mirrorX;
        else if (dragging == GizmoHandle.MIRROR_Z) mirrorZ = !mirrorZ;
    }

    /** Convert the current view ray into an offset or an angle for the active drag. */
    private void drag(final Vector3d pivot, final Vector3d eye, final Vector3d direction) {
        // Measure against the pivot the drag started from, not the one being
        // drawn: the drawn pivot follows the pending move.
        final Vector3d anchor = dragPivot != null ? dragPivot : pivot;
        if (dragging.kind() == GizmoHandle.Kind.MOVE) {
            // The plane must follow the view: it is what keeps the drag
            // meaningful while the player looks around.
            dragPlaneNormal = dragPlane(direction);
        }
        final Vector3d hit = GizmoMath.rayPlane(eye, direction, anchor, dragPlaneNormal);
        if (hit == null) return;
        if (dragStart == null) {
            // The plane was edge-on to the ray when the drag began; anchor on
            // the first usable hit so the handle is still grabbable.
            dragStart = hit;
            if (dragging.kind() == GizmoHandle.Kind.ROTATE) {
                dragStartAngle = angleOf(hit, pivot, dragging);
            }
            return;
        }
        switch (dragging.kind()) {
            case MOVE -> {
                final Vector3d delta = new Vector3d(hit).sub(dragStart);
                applyOffset(dragDirection, delta.dot(dragDirection));
            }
            case MOVE_PLANE -> {
                final Vector3d delta = new Vector3d(hit).sub(dragStart);
                offsetX = snapVoxels(delta.x);
                offsetY = 0;
                offsetZ = snapVoxels(delta.z);
            }
            case ROTATE -> {
                final double current = angleOf(hit, pivot, dragging);
                final double delta = GizmoMath.signedAngle(
                    directionAt(pivot, dragging, dragStartAngle),
                    directionAt(pivot, dragging, current), dragging.direction());
                angle = snapAngle(angle + delta);
                dragStartAngle = current;
            }
            case MIRROR -> {
                // Mirrors are toggled on release, so dragging changes nothing.
            }
            default -> {
            }
        }
    }

    /**
     * The plane a translation drag reads its movement from: the one that
     * contains the handle's axis and faces the player. Looking straight down
     * the axis leaves it undefined, so any plane containing the axis is used
     * until the view tilts off.
     */
    private Vector3d dragPlane(final Vector3d direction) {
        final Vector3d normal = GizmoMath.reject(direction, dragDirection);
        if (normal.lengthSquared() < 1e-9) {
            final Vector3d seed = Math.abs(dragDirection.y) < 0.9
                ? new Vector3d(0, 1, 0) : new Vector3d(0, 0, 1);
            return new Vector3d(seed).cross(dragDirection).normalize();
        }
        return normal.normalize();
    }

    /** Apply a signed distance along one axis to the matching offset. */
    private void applyOffset(final Vector3d axis, final double amount) {
        final long voxels = snapVoxels(amount);
        if (Math.abs(axis.x) > 0.5) offsetX = voxels;
        else if (Math.abs(axis.y) > 0.5) offsetY = voxels;
        else offsetZ = voxels;
    }

    private long snapVoxels(final double blocks) {
        final double voxels = blocks * VoxelBox.VOXELS_PER_BLOCK;
        return Math.round(GizmoMath.snap(voxels, SNAP_VOXELS));
    }

    private double snapAngle(final double radians) {
        final double step = Math.toRadians(angleStep());
        return step <= 0 ? radians : GizmoMath.snap(radians, step);
    }

    /** The direction from the pivot to a point on the handle's plane. */
    private static Vector3d directionAt(final Vector3d pivot, final GizmoHandle handle,
                                        final double angle) {
        final Vector3d axis = handle.direction();
        // Any pair of axes perpendicular to the rotation axis works.
        final Vector3d u = GizmoMath.reject(new Vector3d(1, 0, 0), axis).normalize();
        final Vector3d v = new Vector3d(axis).cross(u).normalize();
        return new Vector3d(u).mul(Math.cos(angle)).add(new Vector3d(v).mul(Math.sin(angle)));
    }

    private static double angleOf(final Vector3d point, final Vector3d pivot,
                                  final GizmoHandle handle) {
        if (point == null) return 0;
        final Vector3d offset = new Vector3d(point).sub(pivot);
        final Vector3d axis = handle.direction();
        final Vector3d u = GizmoMath.reject(new Vector3d(1, 0, 0), axis).normalize();
        final Vector3d v = new Vector3d(axis).cross(u).normalize();
        return Math.atan2(offset.dot(v), offset.dot(u));
    }

    /** The nearest handle along the view ray, or {@code null}. */
    private GizmoHandle pick(final Vector3d pivot, final Vector3d eye, final Vector3d direction) {
        if (eye.distance(pivot) > MAX_REACH) return null;
        double nearest = Double.MAX_VALUE;
        GizmoHandle best = null;
        for (final GizmoHandle handle : GizmoHandle.values()) {
            final double hit = hit(handle, pivot, eye, direction);
            if (hit >= 0 && hit < nearest) {
                nearest = hit;
                best = handle;
            }
        }
        return best;
    }

    /** How far along the ray a handle is hit, or {@code -1}. */
    private double hit(final GizmoHandle handle, final Vector3d pivot,
                       final Vector3d eye, final Vector3d direction) {
        final double radius = AXIS_RADIUS + PICK_SLACK;
        return switch (handle.kind()) {
            case MOVE -> Math.min(
                hitOrMiss(GizmoMath.raySegment(eye, direction,
                    axisPoint(pivot, handle, AXIS_INNER),
                    axisPoint(pivot, handle, AXIS_OUTER), radius)),
                hitOrMiss(GizmoMath.rayPoint(eye, direction,
                    axisPoint(pivot, handle, AXIS_OUTER), TIP_HALF + radius)));
            case MIRROR -> GizmoMath.rayPoint(eye, direction,
                axisPoint(pivot, handle, -MIRROR_RADIUS),
                MIRROR_HALF + radius);
            case MOVE_PLANE -> planeHit(handle, pivot, eye, direction);
            case ROTATE -> ringHit(handle, pivot, eye, direction);
        };
    }

    private static double hitOrMiss(final double hit) {
        return hit < 0 ? Double.MAX_VALUE : hit;
    }

    /** A point along the handle's axis, in world space. */
    private static Vector3d axisPoint(final Vector3d pivot, final GizmoHandle handle,
                                      final double along) {
        return new Vector3d(pivot).fma(along, handle.direction());
    }

    /** A hit on the horizontal plane handle's square. */
    private double planeHit(final GizmoHandle handle, final Vector3d pivot,
                            final Vector3d eye, final Vector3d direction) {
        final Vector3d point = GizmoMath.rayPlane(eye, direction, pivot, handle.direction());
        if (point == null) return -1;
        final Vector3d local = new Vector3d(point).sub(pivot);
        if (Math.abs(local.x) > PLANE_OUTER || Math.abs(local.z) > PLANE_OUTER) return -1;
        if (Math.abs(local.x) < PLANE_INNER || Math.abs(local.z) < PLANE_INNER) return -1;
        return eye.distance(point);
    }

    /** A hit on the rotation ring's arc. */
    private double ringHit(final GizmoHandle handle, final Vector3d pivot,
                           final Vector3d eye, final Vector3d direction) {
        final Vector3d point = GizmoMath.rayPlane(eye, direction, pivot, handle.direction());
        if (point == null) return -1;
        final Vector3d local = new Vector3d(point).sub(pivot);
        final double distance = Math.hypot(local.x, local.z);
        if (Math.abs(distance - RING_RADIUS) > RING_THICKNESS + PICK_SLACK) return -1;
        // The ring is a full circle, so any point around it is a hit. The
        // horizontal ring is drawn in world X/Z; other axes are not offered.
        return eye.distance(point);
    }

    // =====================================================================
    //  Preview
    // =====================================================================

    /** Draw every handle, highlighting the hovered and active ones. */
    public void preview(final PreviewScene scene, final Vector3d pivot) {
        for (final GizmoHandle handle : GizmoHandle.values()) {
            final boolean active = handle == dragging;
            final boolean hot = active || (dragging == null && handle == hovered);
            final int color = hot ? Colors.CURSOR : dim(handle.color());
            draw(scene, handle, pivot, color, active);
        }
        drawAngle(scene, pivot);
    }

    /**
     * Draw the pending rotation on the ring: a spoke from the pivot to where
     * the drag started, and a spoke to the current angle, so the amount turned
     * is visible in the world.
     *
     * <p>Two line segments rather than a filled wedge. A wedge is a triangle
     * per tessellation step, and the whole set has to be re-sent every time the
     * angle changes; two spokes are two entities that move. The angle itself is
     * reported numerically through the status text, since the preview shapes
     * cannot carry text.</p>
     */
    private void drawAngle(final PreviewScene scene, final Vector3d pivot) {
        final String key = prefix + "angle";
        if (angle == 0 || dragging == null || dragging.kind() != GizmoHandle.Kind.ROTATE) {
            scene.remove(key + ".from");
            scene.remove(key + ".to");
            return;
        }
        final Vector3d axis = dragging.direction();
        final int color = Colors.CURSOR;
        final float thickness = 0.03f;

        // Where the drag started, dimmed; where it is now, bright.
        scene.polyline(key + ".from", List.of(
                f(pivot), f(ringPoint(pivot, axis, dragStartAngle))),
            false, thickness, Colors.withAlpha(color, 0x60));
        scene.polyline(key + ".to", List.of(
                f(pivot), f(ringPoint(pivot, axis, dragStartAngle + angle))),
            false, thickness, color);
    }

    /**
     * A point on the rotation ring at {@code arc} radians, in world space.
     * Shares {@link #ring}'s basis so the spokes and the ring agree.
     */
    private Vector3d ringPoint(final Vector3d pivot, final Vector3d axis, final double arc) {
        final double radius = RING_RADIUS;
        if (Math.abs(axis.y) > 0.5) {
            // The horizontal ring: the same world X/Z basis the ring is drawn in.
            return new Vector3d(
                pivot.x + Math.cos(arc) * radius, pivot.y, pivot.z + Math.sin(arc) * radius);
        }
        final Vector3d u = GizmoMath.reject(new Vector3d(0, 1, 0), axis).normalize();
        final Vector3d v = new Vector3d(axis).cross(u).normalize();
        return new Vector3d(pivot)
            .fma(Math.cos(arc) * radius, u)
            .fma(Math.sin(arc) * radius, v);
    }

    private static Vector3f f(final Vector3d point) {
        return new Vector3f((float) point.x, (float) point.y, (float) point.z);
    }

    /** Remove every handle. */
    public void clear(final PreviewScene scene) {
        scene.removePrefix(prefix);
    }

    private void draw(final PreviewScene scene, final GizmoHandle handle, final Vector3d pivot,
                      final int color, final boolean active) {
        final String key = prefix + handle.id();
        switch (handle.kind()) {
            case MOVE -> {
                // Filled rather than outlined, so the handle reads as a solid
                // bar and the shaft can stay thin without disappearing.
                scene.faces(key + ".shaft",
                    axisBox(pivot, handle, AXIS_INNER, AXIS_OUTER, AXIS_RADIUS),
                    axisBoxMax(pivot, handle, AXIS_INNER, AXIS_OUTER, AXIS_RADIUS),
                    solid(color));
                scene.faces(key + ".tip",
                    axisBox(pivot, handle, AXIS_OUTER - TIP_HALF, AXIS_OUTER + TIP_HALF, TIP_HALF),
                    axisBoxMax(pivot, handle, AXIS_OUTER - TIP_HALF, AXIS_OUTER + TIP_HALF, TIP_HALF),
                    solid(color));
            }
            case MOVE_PLANE -> scene.faces(key + ".plane",
                new Vector3f((float) (pivot.x + PLANE_INNER), (float) (pivot.y - 0.01),
                    (float) (pivot.z + PLANE_INNER)),
                new Vector3f((float) (pivot.x + PLANE_OUTER), (float) (pivot.y + 0.01),
                    (float) (pivot.z + PLANE_OUTER)),
                active ? color : Colors.withAlpha(color, 0x50));
            case ROTATE -> scene.polyline(key + ".ring", ring(pivot), false, 0.03f, color);
            case MIRROR -> scene.faces(key + ".cube",
                axisBox(pivot, handle, -(MIRROR_RADIUS + MIRROR_HALF),
                    -(MIRROR_RADIUS - MIRROR_HALF), MIRROR_HALF),
                axisBoxMax(pivot, handle, -(MIRROR_RADIUS + MIRROR_HALF),
                    -(MIRROR_RADIUS - MIRROR_HALF), MIRROR_HALF),
                solid(color));
        }
    }

    /**
     * The low corner of a box around a stretch of the handle's axis. The box
     * is thick on the two axes the handle does not run along.
     */
    private static Vector3f axisBox(final Vector3d pivot, final GizmoHandle handle,
                                    final double from, final double to, final double radius) {
        final Vector3d a = new Vector3d(pivot).fma(from, handle.direction());
        final Vector3d b = new Vector3d(pivot).fma(to, handle.direction());
        final Vector3d axis = handle.direction();
        return new Vector3f(
            (float) (Math.min(a.x, b.x) - (1 - Math.abs(axis.x)) * radius),
            (float) (Math.min(a.y, b.y) - (1 - Math.abs(axis.y)) * radius),
            (float) (Math.min(a.z, b.z) - (1 - Math.abs(axis.z)) * radius));
    }

    private static Vector3f axisBoxMax(final Vector3d pivot, final GizmoHandle handle,
                                       final double from, final double to, final double radius) {
        final Vector3d a = new Vector3d(pivot).fma(from, handle.direction());
        final Vector3d b = new Vector3d(pivot).fma(to, handle.direction());
        final Vector3d axis = handle.direction();
        return new Vector3f(
            (float) (Math.max(a.x, b.x) + (1 - Math.abs(axis.x)) * radius),
            (float) (Math.max(a.y, b.y) + (1 - Math.abs(axis.y)) * radius),
            (float) (Math.max(a.z, b.z) + (1 - Math.abs(axis.z)) * radius));
    }

    /** The rotation ring's arc, in world space. */
    private List<Vector3f> ring(final Vector3d pivot) {
        final List<Vector3f> points = new ArrayList<>(RING_SEGMENTS + 1);
        for (int index = 0; index <= RING_SEGMENTS; index++) {
            final double arc = RING_SPAN * index / RING_SEGMENTS;
            points.add(new Vector3f(
                (float) (pivot.x + Math.cos(arc) * RING_RADIUS),
                (float) pivot.y,
                (float) (pivot.z + Math.sin(arc) * RING_RADIUS)));
        }
        return points;
    }

    /** The drawn ring, for tests. */
    List<Vector3f> ringForTest(final Vector3d pivot) {
        return ring(pivot);
    }

    /** The ring point at {@code arc} radians, for tests. */
    Vector3d ringPointForTest(final Vector3d pivot, final Vector3d axis, final double arc) {
        return ringPoint(pivot, axis, arc);
    }

    /** A solid handle face: the color at high alpha. */
    private static int solid(final int argb) {
        return Colors.withAlpha(argb, 0xC0);
    }

    /** Dim a handle's color so the hovered one stands out. */
    private static int dim(final int argb) {
        final int alpha = (argb >>> 24) & 0xFF;
        return Colors.withAlpha(argb, Math.max(0x40, alpha / 2));
    }
}
