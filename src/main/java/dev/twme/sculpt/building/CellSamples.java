package dev.twme.sculpt.building;

import java.util.BitSet;
import java.util.Map;

import org.bukkit.block.data.BlockData;

import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.core.PlayerHeadTexture;

/**
 * Cell occupancy and materials read from the world at one resolution.
 * Regular blocks count as occupied; their material is only known when they
 * could be converted into a SculptBlock.
 */
public final class CellSamples implements BrushSmoother.CellLookup {

    record Sample(BitSet occupied, BlockData[] materials, PlayerHeadTexture[] textures) {}

    private final int grid;
    private final Map<BlockPos, Sample> samples;

    CellSamples(final int grid, final Map<BlockPos, Sample> samples) {
        this.grid = grid;
        this.samples = samples;
    }

    /**
     * Samples from explicit cell materials, for example in tests. Each array
     * holds {@code grid³} entries indexed by {@link CellVolume#localIndex};
     * {@code null} entries are empty cells.
     */
    public static CellSamples of(final int grid, final Map<BlockPos, CellMaterial[]> cells) {
        final Map<BlockPos, Sample> samples = new java.util.HashMap<>();
        cells.forEach((position, materials) -> {
            final BitSet occupied = new BitSet(materials.length);
            final BlockData[] data = new BlockData[materials.length];
            final PlayerHeadTexture[] textures = new PlayerHeadTexture[materials.length];
            for (int index = 0; index < materials.length; index++) {
                if (materials[index] == null) continue;
                occupied.set(index);
                data[index] = materials[index].blockData();
                textures[index] = materials[index].playerHeadTexture();
            }
            samples.put(position, new Sample(occupied, data, textures));
        });
        return new CellSamples(grid, samples);
    }

    public int grid() {
        return grid;
    }

    @Override
    public boolean occupied(final long x, final long y, final long z) {
        final Sample sample = sampleAt(x, y, z);
        return sample != null && sample.occupied().get(index(x, y, z));
    }

    @Override
    public BlockData material(final long x, final long y, final long z) {
        final Sample sample = sampleAt(x, y, z);
        return sample == null ? null : sample.materials()[index(x, y, z)];
    }

    /** The editable material of an occupied cell, including a held head texture. */
    public CellMaterial cellMaterial(final long x, final long y, final long z) {
        final Sample sample = sampleAt(x, y, z);
        if (sample == null) return null;
        final int index = index(x, y, z);
        final BlockData data = sample.materials()[index];
        return data == null ? null : new CellMaterial(data, sample.textures()[index]);
    }

    private Sample sampleAt(final long x, final long y, final long z) {
        return samples.get(new BlockPos(
            (int) Math.floorDiv(x, grid),
            (int) Math.floorDiv(y, grid),
            (int) Math.floorDiv(z, grid)));
    }

    private int index(final long x, final long y, final long z) {
        return CellVolume.localIndex(grid,
            (int) Math.floorMod(x, grid),
            (int) Math.floorMod(y, grid),
            (int) Math.floorMod(z, grid));
    }
}
