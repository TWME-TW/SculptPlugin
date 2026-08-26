package dev.twme.sculpt.plugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.twme.sculpt.assets.shape.VisualShape;
import dev.twme.sculpt.assets.shape.VoxelMask;

/** Pure world-space union and split stage for visual-shape replacement. */
public final class VisualShapeReplacePlanner {

    private VisualShapeReplacePlanner() {}

    public static Plan plan(
            final Bounds bounds,
            final Collection<Source> sources) {
        final Map<Position, VoxelMask.Builder> output = new LinkedHashMap<>();
        for (final Source source : sources) {
            for (final Map.Entry<VisualShape.BlockOffset, VoxelMask> entry
                    : source.shape().blocks().entrySet()) {
                final VisualShape.BlockOffset offset = entry.getKey();
                final Position target = new Position(
                    source.position().x() + offset.x(),
                    source.position().y() + offset.y(),
                    source.position().z() + offset.z());
                if (!bounds.contains(target)) {
                    return Plan.outOfBounds(target);
                }
                output.computeIfAbsent(target, ignored -> VoxelMask.builder())
                    .add(entry.getValue());
            }
        }

        final Map<Position, VoxelMask> masks = new LinkedHashMap<>();
        long generatedLeaves = 0L;
        for (final Map.Entry<Position, VoxelMask.Builder> entry : output.entrySet()) {
            final VoxelMask mask = entry.getValue().build();
            if (mask.isEmpty()) continue;
            masks.put(entry.getKey(), mask);
            if (!mask.isFull()) {
                generatedLeaves += mask.compressedOccupiedLeafCount();
            }
        }
        return new Plan(Map.copyOf(masks), generatedLeaves, null);
    }

    public record Position(int x, int y, int z) {}

    public record Bounds(
        int minX, int maxX,
        int minY, int maxY,
        int minZ, int maxZ
    ) {
        public boolean contains(final Position position) {
            return position.x() >= minX && position.x() <= maxX
                && position.y() >= minY && position.y() <= maxY
                && position.z() >= minZ && position.z() <= maxZ;
        }
    }

    public record Source(Position position, VisualShape shape) {
        public Source {
            if (position == null || shape == null) {
                throw new IllegalArgumentException("position and shape are required");
            }
        }

        public static Source fromMask(
                final Position position,
                final VoxelMask mask) {
            final VisualShape shape = VisualShape.builder()
                .addBlock(VisualShape.BlockOffset.ORIGIN, mask)
                .build();
            return new Source(position, shape);
        }
    }

    public record Plan(
        Map<Position, VoxelMask> masks,
        long generatedLeaves,
        Position outOfBoundsPosition
    ) {
        private static Plan outOfBounds(final Position position) {
            return new Plan(Map.of(), 0L, position);
        }

        public boolean outOfBounds() {
            return outOfBoundsPosition != null;
        }
    }
}
