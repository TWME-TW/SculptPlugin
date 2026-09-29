package dev.twme.sculpt.editor.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.BitSet;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.building.BlockCellEdit;
import dev.twme.sculpt.building.BlockPos;
import dev.twme.sculpt.building.BuildLimits;
import dev.twme.sculpt.building.CellSamples;
import dev.twme.sculpt.building.CellVolume;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.editor.VoxelBox;
import dev.twme.sculpt.editor.VoxelTransform;

class EditorToolLogicTest {

    private static final CellMaterial STONE = CellMaterial.block(blockData(Material.STONE));

    @Test
    void movingCarvesTheSourceAndFillsTheDestination() {
        // One stone voxel at the origin of block (0,0,0); move it one block east.
        final CellMaterial[] cells = new CellMaterial[4096];
        cells[CellVolume.localIndex(16, 0, 0, 0)] = STONE;
        final CellSamples samples = CellSamples.of(16, Map.of(new BlockPos(0, 0, 0), cells));
        final VoxelTransform transform = new VoxelTransform(
            new VoxelBox(0, 0, 0, 1, 1, 1), 0, false, false, 16, 0, 0);

        final Map<BlockPos, BlockCellEdit> edits = TransformTool.edits(transform, samples, true);

        final BlockCellEdit source = edits.get(new BlockPos(0, 0, 0));
        assertEquals(BlockCellEdit.Operation.CARVE, source.layers().get(0).operation());
        final BlockCellEdit destination = edits.get(new BlockPos(1, 0, 0));
        final BlockCellEdit.Layer add = destination.layers().get(destination.layers().size() - 1);
        assertEquals(BlockCellEdit.Operation.ADD, add.operation());
        assertTrue(add.cells().get(CellVolume.localIndex(16, 0, 0, 0)));
    }

    @Test
    void copyingKeepsTheSourceAndClearsEmptyDestinationVoxels() {
        final CellMaterial[] cells = new CellMaterial[4096];
        cells[CellVolume.localIndex(16, 0, 0, 0)] = STONE;
        final CellSamples samples = CellSamples.of(16, Map.of(new BlockPos(0, 0, 0), cells));
        final VoxelTransform transform = new VoxelTransform(
            new VoxelBox(0, 0, 0, 2, 1, 1), 0, false, false, 16, 0, 0);

        final Map<BlockPos, BlockCellEdit> edits = TransformTool.edits(transform, samples, false);

        assertNull(edits.get(new BlockPos(0, 0, 0)), "copies leave the source untouched");
        final BlockCellEdit destination = edits.get(new BlockPos(1, 0, 0));
        final BitSet carved = destination.layers().get(0).cells();
        assertTrue(carved.get(CellVolume.localIndex(16, 1, 0, 0)),
            "an empty source voxel empties its destination");
    }

    @Test
    void selectionCellsUseTheCoarsestAlignedResolution() {
        final BuildLimits limits = BuildLimits.defaults();
        assertEquals(1, SelectionActions.cells(new VoxelBox(0, 0, 0, 32, 16, 16), limits).grid());
        final CellVolume halves = SelectionActions.cells(new VoxelBox(0, 0, 0, 8, 8, 8), limits);
        assertEquals(2, halves.grid());
        assertEquals(1, halves.cellCount());
        assertEquals(16, SelectionActions.cells(new VoxelBox(1, 0, 0, 2, 1, 1), limits).grid());
    }

    @Test
    void surfaceRowsPreferSquareNets() {
        assertEquals(2, ShapeTool.surfaceRows(4, 0));
        assertEquals(3, ShapeTool.surfaceRows(9, 0));
        assertEquals(2, ShapeTool.surfaceRows(6, 0));
        assertEquals(-1, ShapeTool.surfaceRows(5, 0));
        assertEquals(3, ShapeTool.surfaceRows(12, 3));
        assertEquals(-1, ShapeTool.surfaceRows(12, 5));
        assertEquals(-1, ShapeTool.surfaceRows(4, 4));
    }

    @Test
    void cylinderRadiusIsMeasuredFromTheAxis() {
        assertEquals(3.0, ShapeTool.distanceToAxis(new Vector3d(3, 5, 0),
            new Vector3d(0, 0, 0), new Vector3d(0, 10, 0)), 1e-9);
    }

    @Test
    void shapeValidationReportsPointCounts() {
        assertEquals("building.shape.plane.points", new ShapeTool().validate());
    }

    private static BlockData blockData(final Material material) {
        final String serialized = "minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT);
        final BlockData[] self = new BlockData[1];
        self[0] = (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> args[0] instanceof BlockData other && serialized.equals(other.getAsString());
                case "hashCode" -> serialized.hashCode();
                case "toString", "getAsString" -> serialized;
                case "clone" -> self[0];
                case "getMaterial" -> material;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return self[0];
    }
}
