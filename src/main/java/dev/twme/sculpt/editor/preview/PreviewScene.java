package dev.twme.sculpt.editor.preview;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Location;
import org.joml.Vector3f;

import dev.twme.textdisplayshape.packet.PacketPolyline;
import dev.twme.textdisplayshape.packet.PacketShapeFactory;
import dev.twme.textdisplayshape.packet.PacketTriangle;
import dev.twme.textdisplayshape.shape.BoxFaces;
import dev.twme.textdisplayshape.shape.BoxOutline;
import dev.twme.textdisplayshape.shape.Shape;
import dev.twme.textdisplayshape.shape.ShapeGroup;
import dev.twme.textdisplayshape.shape.ShapeStyle;

/**
 * The packet-only preview shapes of one editing player. Nothing here exists
 * on the server or is visible to anyone else.
 *
 * <p>Elements are addressed by key and updated in place, so the client
 * animates changes with Display interpolation instead of respawning. Updates
 * are only sent when the requested geometry actually changed, so an animation
 * runs once instead of being restarted every tick. Every method must run on
 * the player's thread.</p>
 *
 * <p>The origin is both the Text Display position of every shape and the
 * reference point of their world-space geometry, so it must stay axis
 * aligned: a Display applies its transformation <em>after</em> the entity's
 * own orientation, which means an origin carrying the player's view rotation
 * would rotate every preview around the player. {@link #follow(Location)}
 * keeps the origin near the player without moving anything on screen.</p>
 */
public final class PreviewScene {

    /** Interpolation used when an element changes shape, size, or color. */
    public static final int UPDATE_TICKS = 2;
    /** Ticks a pulse holds its color before it starts to fade out. */
    private static final int PULSE_HOLD_TICKS = 2;
    private static final int PULSE_FADE_TICKS = 6;
    private static final float VIEW_RANGE = 4.0f;
    /** Distance the player may move before the scene rebases its origin. */
    private static final double REBASE_DISTANCE_SQUARED = 8.0 * 8.0;

    private final PacketShapeFactory shapes;
    private final UUID viewer;
    private final Location origin;
    private final int budget;
    private final Map<String, Element> elements = new LinkedHashMap<>();
    private final List<Pulse> pulses = new ArrayList<>();
    private int pulseCounter;
    private boolean overBudget;
    private boolean animations;

    public PreviewScene(
            final PacketShapeFactory shapes,
            final UUID viewer,
            final Location origin,
            final int budget,
            final boolean animations) {
        this.shapes = shapes;
        this.viewer = viewer;
        this.origin = aligned(origin);
        this.budget = budget;
        this.animations = animations;
    }

    /**
     * Apply an animation setting to the whole scene, including the elements
     * that already exist.
     */
    public void setAnimations(final boolean enabled) {
        if (animations == enabled) return;
        animations = enabled;
        final int ticks = enabled ? UPDATE_TICKS : 0;
        for (final Element element : elements.values()) {
            element.shape().setInterpolationDuration(ticks);
        }
    }

    /** Whether previews animate for this player. */
    public boolean animations() {
        return animations;
    }

    /** Whether the last update had to skip elements to stay within the budget. */
    public boolean overBudget() {
        return overBudget;
    }

    public int entityCount() {
        int count = 0;
        for (final Element element : elements.values()) count += element.shape().getEntityCount();
        for (final Pulse pulse : pulses) count += pulse.faces().getEntityCount();
        return count;
    }

    // =====================================================================
    //  Elements
    // =====================================================================

    /** A box outline, created or moved in place. */
    public void outline(final String key, final Vector3f min, final Vector3f max, final int argb) {
        final Element existing = elements.get(key);
        if (existing != null && existing.shape() instanceof BoxOutline outline) {
            if (!outline.getMin().equals(min) || !outline.getMax().equals(max)) {
                outline.setBounds(min, max);
            }
            recolor(existing, argb);
            return;
        }
        remove(key);
        if (!fits(12)) return;
        final float size = Math.min(max.x - min.x, Math.min(max.y - min.y, max.z - min.z));
        final float thickness = Math.clamp(size * 0.04f, 0.006f, 0.05f);
        final BoxOutline outline = shapes.boxOutline(origin, min, max, thickness, style(argb));
        add(key, outline, argb);
    }

    /** Translucent box faces, created or moved in place. */
    public void faces(final String key, final Vector3f min, final Vector3f max, final int argb) {
        final Element existing = elements.get(key);
        if (existing != null && existing.shape() instanceof BoxFaces faces) {
            if (!faces.getMin().equals(min) || !faces.getMax().equals(max)) {
                faces.setBounds(min, max);
            }
            recolor(existing, argb);
            return;
        }
        remove(key);
        if (!fits(6)) return;
        add(key, shapes.boxFaces(origin, min, max, style(argb)), argb);
    }

    /** A polyline through the points, created or updated in place. */
    public void polyline(
            final String key,
            final List<Vector3f> points,
            final boolean closed,
            final float thickness,
            final int argb) {
        if (points.size() < 2) {
            remove(key);
            return;
        }
        final Element existing = elements.get(key);
        if (existing != null && existing.shape() instanceof PacketPolyline polyline
                && existing.closed() == closed) {
            recolor(existing, argb);
            if (same(points, existing.points())) return;
            final int extra = segments(points.size(), closed) - polyline.getSegmentCount();
            if (extra > 0 && !fits(extra)) return;
            polyline.setPoints(points);
            existing.setPoints(points);
            return;
        }
        remove(key);
        if (!fits(segments(points.size(), closed))) return;
        final PacketPolyline polyline = shapes.polyline(origin, points, thickness)
            .closed(closed).style(style(argb)).build();
        add(key, polyline, argb, points, closed);
    }

    /**
     * Double-sided translucent triangles, reusing existing triangles so the
     * surface morphs smoothly while control points move.
     */
    public void triangles(final String key, final List<Vector3f[]> input, final int argb) {
        final List<Vector3f[]> triangles = new ArrayList<>(input.size());
        for (final Vector3f[] triangle : input) {
            if (!degenerate(triangle)) triangles.add(triangle);
        }
        Element element = elements.get(key);
        final ShapeGroup group;
        if (element != null && element.shape() instanceof ShapeGroup found
                && !(found instanceof BoxOutline) && !(found instanceof BoxFaces)) {
            group = found;
            recolor(element, argb);
            if (same(flatten(triangles), element.points())) return;
        } else {
            remove(key);
            group = new ShapeGroup();
            group.addViewer(viewer);
            group.spawn();
            element = add(key, group, argb);
        }
        final List<Shape> members = group.getMembers();
        for (int index = 0; index < triangles.size(); index++) {
            final Vector3f[] triangle = triangles.get(index);
            if (index < members.size()) {
                ((PacketTriangle) members.get(index)).setPoints(triangle[0], triangle[1], triangle[2]);
            } else {
                if (!fits(6)) break;
                group.add(shapes.triangle(origin, triangle[0], triangle[1], triangle[2])
                    .style(style(argb).withDoubleSided(true)).build());
            }
        }
        for (int index = members.size() - 1; index >= triangles.size(); index--) {
            group.remove(members.get(index));
        }
        element.setPoints(flatten(triangles));
    }

    public void remove(final String key) {
        final Element element = elements.remove(key);
        if (element != null) element.shape().remove();
    }

    /** Remove every element whose key starts with {@code prefix}. */
    public void removePrefix(final String prefix) {
        final Iterator<Map.Entry<String, Element>> iterator = elements.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, Element> entry = iterator.next();
            if (!entry.getKey().startsWith(prefix)) continue;
            entry.getValue().shape().remove();
            iterator.remove();
        }
    }

    public boolean has(final String key) {
        return elements.containsKey(key);
    }

    // =====================================================================
    //  Following the player
    // =====================================================================

    /**
     * Rebase the origin onto the player once they have moved far enough. The
     * shape origins and their stored translations move by the same amount, so
     * the previews stay where they are on screen while the Text Display
     * entities stay next to the viewer and keep small translations.
     */
    public void follow(final Location player) {
        if (player == null || !Objects.equals(player.getWorld(), origin.getWorld())) return;
        final double x = player.getX();
        final double y = player.getY();
        final double z = player.getZ();
        final double dx = x - origin.getX();
        final double dy = y - origin.getY();
        final double dz = z - origin.getZ();
        if (dx * dx + dy * dy + dz * dz < REBASE_DISTANCE_SQUARED) return;
        shapes.batch(() -> {
            for (final Element element : elements.values()) element.shape().teleportOrigin(x, y, z);
            for (final Pulse pulse : pulses) pulse.faces().teleportOrigin(x, y, z);
        });
        origin.setX(x);
        origin.setY(y);
        origin.setZ(z);
    }

    // =====================================================================
    //  Pulses
    // =====================================================================

    /**
     * Briefly highlight a region: hold the color for a moment, then fade it
     * out. Used to show what a commit, undo, or redo changed.
     */
    public void pulse(final Vector3f min, final Vector3f max, final int argb) {
        if (!animations || !fits(6)) return;
        final BoxFaces faces = shapes.boxFaces(origin,
            new Vector3f(min).sub(0.01f, 0.01f, 0.01f), new Vector3f(max).add(0.01f, 0.01f, 0.01f),
            style(argb).withInterpolationDuration(PULSE_FADE_TICKS));
        faces.addViewer(viewer);
        faces.spawn();
        // tick() advances the counter before it runs the pulses, so the first
        // check after this frame happens on the next tick.
        final int fadeAt = pulseCounter + PULSE_HOLD_TICKS;
        pulses.add(new Pulse(faces, fadeAt, fadeAt + PULSE_FADE_TICKS + 1));
    }

    /** Advance pulse animations; call once per tick. */
    public void tick() {
        pulseCounter++;
        final Iterator<Pulse> iterator = pulses.iterator();
        while (iterator.hasNext()) {
            final Pulse pulse = iterator.next();
            if (pulseCounter == pulse.fadeAt()) {
                pulse.faces().setColor(pulse.faces().getColor() & 0x00FFFFFF);
            }
            if (pulseCounter >= pulse.removeAt()) {
                pulse.faces().remove();
                iterator.remove();
            }
        }
    }

    /** Remove every element and pulse. */
    public void clear() {
        for (final Element element : elements.values()) element.shape().remove();
        elements.clear();
        for (final Pulse pulse : pulses) pulse.faces().remove();
        pulses.clear();
    }

    /** Start a new frame; elements may report being over budget during it. */
    public void beginFrame() {
        overBudget = false;
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private Element add(final String key, final Shape shape, final int argb) {
        return add(key, shape, argb, List.of(), false);
    }

    private Element add(
            final String key,
            final Shape shape,
            final int argb,
            final List<Vector3f> points,
            final boolean closed) {
        final Element element = new Element(shape, argb, closed, points);
        elements.put(key, element);
        shape.addViewer(viewer);
        shape.spawn();
        return element;
    }

    private void recolor(final Element element, final int argb) {
        if (element.argb() == argb) return;
        element.shape().setColor(argb);
        element.setArgb(argb);
    }

    private boolean fits(final int additional) {
        if (entityCount() + additional <= budget) return true;
        overBudget = true;
        return false;
    }

    private ShapeStyle style(final int argb) {
        return ShapeStyle.DEFAULT
            .withColor(argb)
            .withSeeThrough(true)
            .withViewRange(VIEW_RANGE)
            .withInterpolationDuration(animations ? UPDATE_TICKS : 0);
    }

    /**
     * The given origin without view rotation. Preview geometry is expressed in
     * world coordinates and every shape spawns at this origin, so the player's
     * yaw and pitch must not leak into it.
     */
    static Location aligned(final Location origin) {
        final Location aligned = origin.clone();
        aligned.setYaw(0f);
        aligned.setPitch(0f);
        return aligned;
    }

    private static List<Vector3f> flatten(final List<Vector3f[]> triangles) {
        final List<Vector3f> points = new ArrayList<>(triangles.size() * 3);
        for (final Vector3f[] triangle : triangles) {
            points.add(triangle[0]);
            points.add(triangle[1]);
            points.add(triangle[2]);
        }
        return points;
    }

    /** Whether both lists hold the same points in the same order. */
    static boolean same(final List<Vector3f> first, final List<Vector3f> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            if (!first.get(index).equals(second.get(index))) return false;
        }
        return true;
    }

    private static int segments(final int points, final boolean closed) {
        return closed && points > 2 ? points : points - 1;
    }

    private static boolean degenerate(final Vector3f[] triangle) {
        final Vector3f ab = new Vector3f(triangle[1]).sub(triangle[0]);
        final Vector3f ac = new Vector3f(triangle[2]).sub(triangle[0]);
        return ab.cross(ac).lengthSquared() < 1e-8f;
    }

    /** One preview shape and the last inputs it was updated with. */
    private static final class Element {

        private final Shape shape;
        private final boolean closed;
        private int argb;
        private List<Vector3f> points;

        Element(final Shape shape, final int argb, final boolean closed, final List<Vector3f> points) {
            this.shape = Objects.requireNonNull(shape, "shape");
            this.argb = argb;
            this.closed = closed;
            this.points = List.copyOf(points);
        }

        Shape shape() {
            return shape;
        }

        int argb() {
            return argb;
        }

        boolean closed() {
            return closed;
        }

        List<Vector3f> points() {
            return points;
        }

        void setArgb(final int argb) {
            this.argb = argb;
        }

        void setPoints(final List<Vector3f> points) {
            this.points = List.copyOf(points);
        }
    }

    private record Pulse(BoxFaces faces, int fadeAt, int removeAt) {}
}
