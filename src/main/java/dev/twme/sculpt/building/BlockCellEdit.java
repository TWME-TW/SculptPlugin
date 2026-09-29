package dev.twme.sculpt.building;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

import org.bukkit.block.data.BlockData;

import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.core.PlayerHeadTexture;

/**
 * Cell changes for one world block at one editing resolution. Layers are
 * applied in order, so later layers win where they overlap.
 */
public record BlockCellEdit(int grid, List<Layer> layers) {

    public enum Operation {
        /** Fill cells with a material, replacing whatever occupied them. */
        ADD,
        /** Remove cells. */
        CARVE,
        /** Change the material of occupied cells without changing the shape. */
        PAINT
    }

    /**
     * @param operation   the change applied to every selected cell
     * @param material    required for {@link Operation#ADD} and
     *                    {@link Operation#PAINT}; ignored for carving
     * @param headTexture optional player-head texture for added or painted
     *                    cells; such cells become indivisible head cells
     * @param cells       local cell indices, see {@link CellVolume#localIndex}
     */
    public record Layer(Operation operation, BlockData material,
                        PlayerHeadTexture headTexture, BitSet cells) {
        public Layer {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(cells, "cells");
            if (operation != Operation.CARVE) {
                Objects.requireNonNull(material, "material");
            }
            if (operation == Operation.CARVE) headTexture = null;
        }

        public Layer(final Operation operation, final BlockData material, final BitSet cells) {
            this(operation, material, null, cells);
        }
    }

    public BlockCellEdit {
        if (!CellVolume.isValidGrid(grid)) {
            throw new IllegalArgumentException("invalid grid: " + grid);
        }
        layers = List.copyOf(layers);
    }

    public static BlockCellEdit single(
            final int grid,
            final Operation operation,
            final BlockData material,
            final BitSet cells) {
        return new BlockCellEdit(grid, List.of(new Layer(operation, material, cells)));
    }

    public static BlockCellEdit single(
            final int grid,
            final Operation operation,
            final CellMaterial material,
            final BitSet cells) {
        return new BlockCellEdit(grid, List.of(new Layer(operation,
            material == null ? null : material.blockData(),
            material == null ? null : material.playerHeadTexture(), cells)));
    }

    /** Whether any layer can create cells in an empty position. */
    public boolean adds() {
        return layers.stream().anyMatch(layer -> layer.operation() == Operation.ADD
            && !layer.cells().isEmpty());
    }

    /** First material used to fill cells, or {@code null} for carve/paint only. */
    public BlockData firstAddedMaterial() {
        for (final Layer layer : layers) {
            if (layer.operation() == Operation.ADD && !layer.cells().isEmpty()) {
                return layer.material();
            }
        }
        return null;
    }

    public int cellCount() {
        int count = 0;
        for (final Layer layer : layers) count += layer.cells().cardinality();
        return count;
    }
}
