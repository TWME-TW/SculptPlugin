package dev.twme.sculpt.assets.shape;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Voxelized block model. Entries are block offsets from the source position,
 * allowing model elements to extend across vanilla block boundaries.
 */
public final class VisualShape {

    private final Map<BlockOffset, VoxelMask> blocks;

    private VisualShape(final Map<BlockOffset, VoxelMask> blocks) {
        this.blocks = Map.copyOf(blocks);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<BlockOffset, VoxelMask> blocks() {
        return blocks;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int occupiedVoxelCount() {
        return blocks.values().stream().mapToInt(VoxelMask::occupiedCount).sum();
    }

    public boolean isSingleFullBlock() {
        return blocks.size() == 1
            && VoxelMask.full().equals(blocks.get(BlockOffset.ORIGIN));
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof VisualShape shape && blocks.equals(shape.blocks);
    }

    @Override
    public int hashCode() {
        return blocks.hashCode();
    }

    @Override
    public String toString() {
        return "VisualShape[blocks=" + blocks.size()
            + ", voxels=" + occupiedVoxelCount() + "]";
    }

    public record BlockOffset(int x, int y, int z) {
        public static final BlockOffset ORIGIN = new BlockOffset(0, 0, 0);
    }

    /** Mutable world-local accumulator. */
    public static final class Builder {
        private final Map<BlockOffset, VoxelMask.Builder> blocks =
            new LinkedHashMap<>();

        public Builder setVoxel(final int x, final int y, final int z) {
            final BlockOffset offset = new BlockOffset(
                Math.floorDiv(x, VoxelMask.SIDE),
                Math.floorDiv(y, VoxelMask.SIDE),
                Math.floorDiv(z, VoxelMask.SIDE));
            blocks.computeIfAbsent(offset, ignored -> VoxelMask.builder()).set(
                Math.floorMod(x, VoxelMask.SIDE),
                Math.floorMod(y, VoxelMask.SIDE),
                Math.floorMod(z, VoxelMask.SIDE));
            return this;
        }

        public Builder add(final VisualShape shape) {
            Objects.requireNonNull(shape, "shape");
            for (final Map.Entry<BlockOffset, VoxelMask> entry
                    : shape.blocks.entrySet()) {
                blocks.computeIfAbsent(entry.getKey(), ignored -> VoxelMask.builder())
                    .add(entry.getValue());
            }
            return this;
        }

        public Builder addBlock(final BlockOffset offset, final VoxelMask mask) {
            Objects.requireNonNull(offset, "offset");
            Objects.requireNonNull(mask, "mask");
            if (!mask.isEmpty()) {
                blocks.computeIfAbsent(offset, ignored -> VoxelMask.builder()).add(mask);
            }
            return this;
        }

        public VisualShape build() {
            final Map<BlockOffset, VoxelMask> result = new LinkedHashMap<>();
            blocks.forEach((offset, builder) -> {
                final VoxelMask mask = builder.build();
                if (!mask.isEmpty()) result.put(offset, mask);
            });
            return new VisualShape(result);
        }
    }
}
