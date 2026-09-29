package dev.twme.sculpt.editor.tool;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;
import org.joml.Vector3f;

import dev.twme.sculpt.building.ShapeRasterizer;
import dev.twme.sculpt.editor.CellTarget;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.VoxelBox;

/** Preview helpers shared by several tools. */
final class Previews {

    private static final int RING_SEGMENTS = 24;

    private Previews() {
    }

    /** Outline the targeted cell, or the empty cell in front of it. */
    static void cell(final EditorSession session, final String key, final boolean adjacent, final int color) {
        final CellTarget target = session.target();
        if (target == null) {
            session.scene().remove(key);
            return;
        }
        final VoxelBox cell = target.cell(adjacent);
        session.scene().outline(key, cell.min(), cell.max(), color);
    }

    /**
     * Outline the footprint of a brush of {@code radius} cells centered on a
     * cell: three rings for a sphere, a box for a cube.
     */
    static void brush(
            final EditorSession session,
            final String key,
            final VoxelBox center,
            final int radius,
            final ShapeRasterizer.BrushShape shape,
            final int color) {
        final int side = (int) center.sizeX();
        if (shape == ShapeRasterizer.BrushShape.CUBE) {
            session.scene().removePrefix(key + ".ring");
            final VoxelBox box = center.expand((long) radius * side);
            session.scene().outline(key + ".box", box.min(), box.max(), color);
            return;
        }
        session.scene().remove(key + ".box");
        final Vector3d middle = center.center();
        final double blocks = (radius + 0.5) * side / 16.0;
        session.scene().polyline(key + ".ring.x", ring(middle, blocks, 0), true, 0.02f, color);
        session.scene().polyline(key + ".ring.y", ring(middle, blocks, 1), true, 0.02f, color);
        session.scene().polyline(key + ".ring.z", ring(middle, blocks, 2), true, 0.02f, color);
    }

    /** A circle around {@code center} perpendicular to the given axis (0=X, 1=Y, 2=Z). */
    static List<Vector3f> ring(final Vector3d center, final double radius, final int axis) {
        final List<Vector3f> points = new ArrayList<>(RING_SEGMENTS);
        for (int index = 0; index < RING_SEGMENTS; index++) {
            final double angle = Math.PI * 2 * index / RING_SEGMENTS;
            final double a = Math.cos(angle) * radius;
            final double b = Math.sin(angle) * radius;
            points.add(switch (axis) {
                case 0 -> new Vector3f((float) center.x, (float) (center.y + a), (float) (center.z + b));
                case 1 -> new Vector3f((float) (center.x + a), (float) center.y, (float) (center.z + b));
                default -> new Vector3f((float) (center.x + a), (float) (center.y + b), (float) center.z);
            });
        }
        return points;
    }
}
