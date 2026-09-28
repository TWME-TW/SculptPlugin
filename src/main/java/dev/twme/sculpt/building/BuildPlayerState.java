package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.block.data.BlockData;
import org.joml.Vector3d;

/** Per-player control points and brush settings, kept until the player quits. */
public final class BuildPlayerState {

    public enum BrushMode {
        /** Left-click carves, right-click adds material. */
        SCULPT,
        /** Both clicks smooth spikes and pits. */
        SMOOTH,
        /** Right-click paints, left-click picks the material under the cursor. */
        PAINT
    }

    /** Immutable brush configuration. */
    public record BrushSettings(
        int radius,
        ShapeRasterizer.BrushShape shape,
        BrushMode mode,
        BlockData material
    ) {
        public static BrushSettings defaults() {
            return new BrushSettings(1, ShapeRasterizer.BrushShape.SPHERE, BrushMode.SCULPT, null);
        }

        public BrushSettings withRadius(final int value) {
            return new BrushSettings(value, shape, mode, material);
        }

        public BrushSettings withShape(final ShapeRasterizer.BrushShape value) {
            return new BrushSettings(radius, value, mode, material);
        }

        public BrushSettings withMode(final BrushMode value) {
            return new BrushSettings(radius, shape, value, material);
        }

        public BrushSettings withMaterial(final BlockData value) {
            return new BrushSettings(radius, shape, mode, value == null ? null : value.clone());
        }
    }

    private record Points(UUID worldId, List<Vector3d> points) {}

    private final Map<UUID, Points> points = new ConcurrentHashMap<>();
    private final Map<UUID, BrushSettings> brushes = new ConcurrentHashMap<>();

    /**
     * Append a control point in the given world. Points from another world
     * are discarded first, since a shape cannot span worlds.
     *
     * @return the new number of points, or {@code -1} when the limit is reached
     */
    public int addPoint(final UUID player, final UUID worldId, final Vector3d point) {
        final Points[] result = new Points[1];
        points.compute(player, (ignored, current) -> {
            final List<Vector3d> list = current == null || !current.worldId().equals(worldId)
                ? new ArrayList<>() : new ArrayList<>(current.points());
            if (list.size() >= BuildLimits.MAX_POINTS) {
                result[0] = null;
                return current;
            }
            list.add(new Vector3d(point));
            result[0] = new Points(worldId, List.copyOf(list));
            return result[0];
        });
        return result[0] == null ? -1 : result[0].points().size();
    }

    /** Remove the newest point; returns the remaining count or -1 if none. */
    public int removeLastPoint(final UUID player) {
        final int[] remaining = {-1};
        points.computeIfPresent(player, (ignored, current) -> {
            if (current.points().isEmpty()) return null;
            final List<Vector3d> list = new ArrayList<>(current.points());
            list.removeLast();
            remaining[0] = list.size();
            return list.isEmpty() ? null : new Points(current.worldId(), List.copyOf(list));
        });
        return remaining[0];
    }

    public void clearPoints(final UUID player) {
        points.remove(player);
    }

    /** Copies of the points in {@code worldId}; empty when none or another world. */
    public List<Vector3d> points(final UUID player, final UUID worldId) {
        final Points current = points.get(player);
        if (current == null || !current.worldId().equals(worldId)) return List.of();
        final List<Vector3d> copy = new ArrayList<>(current.points().size());
        for (final Vector3d point : current.points()) copy.add(new Vector3d(point));
        return copy;
    }

    public boolean hasPoints(final UUID player) {
        return points.containsKey(player);
    }

    public BrushSettings brush(final UUID player) {
        return brushes.getOrDefault(player, BrushSettings.defaults());
    }

    public void setBrush(final UUID player, final BrushSettings settings) {
        brushes.put(player, settings);
    }

    public void forget(final UUID player) {
        points.remove(player);
        brushes.remove(player);
    }
}
