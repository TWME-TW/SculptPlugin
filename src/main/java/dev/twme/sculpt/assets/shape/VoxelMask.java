package dev.twme.sculpt.assets.shape;

import java.util.BitSet;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Immutable occupancy mask for one Minecraft block at grid 16. */
public final class VoxelMask {

    public static final int SIDE = 16;
    public static final int VOLUME = SIDE * SIDE * SIDE;
    private static final VoxelMask EMPTY = new VoxelMask(new BitSet(VOLUME));
    private static final VoxelMask FULL;

    static {
        final BitSet full = new BitSet(VOLUME);
        full.set(0, VOLUME);
        FULL = new VoxelMask(full);
    }

    private final BitSet occupied;

    private VoxelMask(final BitSet occupied) {
        this.occupied = (BitSet) occupied.clone();
        this.occupied.clear(VOLUME, Math.max(VOLUME, this.occupied.length()));
    }

    public static VoxelMask empty() {
        return EMPTY;
    }

    public static VoxelMask full() {
        return FULL;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean occupied(final int x, final int y, final int z) {
        return inside(x) && inside(y) && inside(z)
            && occupied.get(index(x, y, z));
    }

    public boolean isEmpty() {
        return occupied.isEmpty();
    }

    public boolean isFull() {
        return occupied.cardinality() == VOLUME;
    }

    public int occupiedCount() {
        return occupied.cardinality();
    }

    /** Number of occupied leaves after recursively collapsing homogeneous octants. */
    public int compressedOccupiedLeafCount() {
        return compressedOccupiedLeafCount(0, 0, 0, SIDE);
    }

    private int compressedOccupiedLeafCount(
            final int minX,
            final int minY,
            final int minZ,
            final int side) {
        boolean any = false;
        boolean all = true;
        for (int y = minY; y < minY + side; y++) {
            for (int z = minZ; z < minZ + side; z++) {
                for (int x = minX; x < minX + side; x++) {
                    final boolean value = occupied.get(index(x, y, z));
                    any |= value;
                    all &= value;
                    if (any && !all) break;
                }
                if (any && !all) break;
            }
            if (any && !all) break;
        }
        if (!any) return 0;
        if (all || side == 1) return 1;

        final int half = side / 2;
        int count = 0;
        for (int octant = 0; octant < 8; octant++) {
            count += compressedOccupiedLeafCount(
                minX + ((octant & 4) == 0 ? 0 : half),
                minY + ((octant & 2) == 0 ? 0 : half),
                minZ + ((octant & 1) == 0 ? 0 : half),
                half);
        }
        return count;
    }

    /** Visit occupied packed indices without exposing mutable BitSet state. */
    public void forEachIndex(final IntConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int index = occupied.nextSetBit(0);
                index >= 0; index = occupied.nextSetBit(index + 1)) {
            consumer.accept(index);
        }
    }

    public static int index(final int x, final int y, final int z) {
        if (!inside(x) || !inside(y) || !inside(z)) {
            throw new IndexOutOfBoundsException(
                "voxel must be inside 0..15: " + x + "," + y + "," + z);
        }
        return (y * SIDE + z) * SIDE + x;
    }

    public static int x(final int index) {
        checkIndex(index);
        return index % SIDE;
    }

    public static int y(final int index) {
        checkIndex(index);
        return index / (SIDE * SIDE);
    }

    public static int z(final int index) {
        checkIndex(index);
        return (index / SIDE) % SIDE;
    }

    private static boolean inside(final int coordinate) {
        return coordinate >= 0 && coordinate < SIDE;
    }

    private static void checkIndex(final int index) {
        if (index < 0 || index >= VOLUME) {
            throw new IndexOutOfBoundsException("voxel index: " + index);
        }
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof VoxelMask mask && occupied.equals(mask.occupied);
    }

    @Override
    public int hashCode() {
        return occupied.hashCode();
    }

    @Override
    public String toString() {
        return "VoxelMask[occupied=" + occupiedCount() + "]";
    }

    /** Mutable accumulator used only while constructing an immutable mask. */
    public static final class Builder {
        private final BitSet occupied = new BitSet(VOLUME);

        public Builder set(final int x, final int y, final int z) {
            occupied.set(index(x, y, z));
            return this;
        }

        public Builder setIndex(final int index) {
            checkIndex(index);
            occupied.set(index);
            return this;
        }

        public Builder add(final VoxelMask mask) {
            occupied.or(mask.occupied);
            return this;
        }

        public VoxelMask build() {
            if (occupied.isEmpty()) return EMPTY;
            if (occupied.cardinality() == VOLUME) return FULL;
            return new VoxelMask(occupied);
        }
    }
}
