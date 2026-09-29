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
 * animates changes with Display interpolation instead of respawning. Every
 * method must run on the player's thread.</p>
 */
public final class PreviewScene {

    /** Interpolation used for cursor and preview updates. */
    public static final int UPDATE_TICKS = 2;
    private static final int PULSE_FADE_TICKS = 6;
    private static final float VIEW_RANGE = 4.0f;

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
        this.origin = origin.clone();
        this.budget = budget;
        this.animations = animations;
    }

    public void setAnimations(final boolean enabled) {
        this.animations = enabled;
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
            final int extra = segments(points.size(), closed) - polyline.getSegmentCount();
            if (extra > 0 && !fits(extra)) return;
            polyline.setPoints(points);
            recolor(existing, argb);
            return;
        }
        remove(key);
        if (!fits(segments(points.size(), closed))) return;
        final PacketPolyline polyline = shapes.polyline(origin, points, thickness)
            .closed(closed).style(style(argb)).build();
        elements.put(key, new Element(polyline, argb, closed));
        polyline.addViewer(viewer);
        polyline.spawn();
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
        final Element existing = elements.get(key);
        final ShapeGroup group;
        if (existing != null && existing.shape() instanceof ShapeGroup found
                && !(found instanceof BoxOutline) && !(found instanceof BoxFaces)) {
            group = found;
            recolor(existing, argb);
        } else {
            remove(key);
            group = new ShapeGroup();
            group.addViewer(viewer);
            group.spawn();
            elements.put(key, new Element(group, argb, false));
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
    //  Pulses
    // =====================================================================

    /**
     * Briefly highlight a region, then fade it out. Used to show what a
     * commit, undo, or redo changed.
     */
    public void pulse(final Vector3f min, final Vector3f max, final int argb) {
        if (!animations || !fits(6)) return;
        final BoxFaces faces = shapes.boxFaces(origin,
            new Vector3f(min).sub(0.01f, 0.01f, 0.01f), new Vector3f(max).add(0.01f, 0.01f, 0.01f),
            style(argb).withInterpolationDuration(PULSE_FADE_TICKS));
        faces.addViewer(viewer);
        faces.spawn();
        pulses.add(new Pulse(faces, pulseCounter + 1, pulseCounter + 2 + PULSE_FADE_TICKS));
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

    private void add(final String key, final Shape shape, final int argb) {
        elements.put(key, new Element(shape, argb, false));
        shape.addViewer(viewer);
        shape.spawn();
    }

    private void recolor(final Element element, final int argb) {
        if (element.argb() == argb) return;
        element.shape().setColor(argb);
        elements.replaceAll((key, value) -> value == element
            ? new Element(value.shape(), argb, value.closed()) : value);
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

    private static int segments(final int points, final boolean closed) {
        return closed && points > 2 ? points : points - 1;
    }

    private static boolean degenerate(final Vector3f[] triangle) {
        final Vector3f ab = new Vector3f(triangle[1]).sub(triangle[0]);
        final Vector3f ac = new Vector3f(triangle[2]).sub(triangle[0]);
        return ab.cross(ac).lengthSquared() < 1e-8f;
    }

    private record Element(Shape shape, int argb, boolean closed) {
        Element {
            Objects.requireNonNull(shape, "shape");
        }
    }

    private record Pulse(BoxFaces faces, int fadeAt, int removeAt) {}
}
