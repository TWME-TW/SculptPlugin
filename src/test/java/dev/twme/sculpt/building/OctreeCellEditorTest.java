package dev.twme.sculpt.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.PlayerHeadTexture;

class OctreeCellEditorTest {

    private static final BlockData STONE = TestBlockData.of(Material.STONE);
    private static final BlockData OAK = TestBlockData.of(Material.OAK_PLANKS);

    @Test
    void carvingOneCellLeavesTheRestOfAFullBlock() {
        final OctreeNode tree = OctreeCellEditor.full(STONE);
        final OctreeCellEditor.Result result = OctreeCellEditor.apply(tree,
            edit(2, BlockCellEdit.Operation.CARVE, null, 0));
        OctreeCellEditor.canonicalize(tree);

        assertTrue(result.changed());
        assertEquals(OctreeCellEditor.Occupancy.PARTIAL, OctreeCellEditor.occupancy(tree));
        assertEquals(7, OctreeCellEditor.occupiedLeaves(tree));
        assertNull(OctreeCellEditor.materialAt(tree, 0, 0, 0));
        assertEquals(STONE, OctreeCellEditor.materialAt(tree, 15, 15, 15));
    }

    @Test
    void fillingEveryCellCollapsesToOneUniformLeaf() {
        final OctreeNode tree = OctreeCellEditor.empty(STONE);
        final BitSet all = new BitSet();
        all.set(0, 64);
        OctreeCellEditor.apply(tree,
            BlockCellEdit.single(4, BlockCellEdit.Operation.ADD, (BlockData) OAK, all));
        OctreeCellEditor.canonicalize(tree);

        assertTrue(tree.isLeaf());
        assertFalse(tree.isRemoved());
        assertEquals(OctreeCellEditor.Occupancy.FULL_UNIFORM, OctreeCellEditor.occupancy(tree));
        assertEquals(OAK, OctreeCellEditor.uniformMaterial(tree));
    }

    @Test
    void carvingEverythingEmptiesTheTree() {
        final OctreeNode tree = OctreeCellEditor.full(STONE);
        final BitSet all = new BitSet();
        all.set(0, 8);
        OctreeCellEditor.apply(tree,
            BlockCellEdit.single(2, BlockCellEdit.Operation.CARVE, (BlockData) null, all));
        OctreeCellEditor.canonicalize(tree);

        assertEquals(OctreeCellEditor.Occupancy.EMPTY, OctreeCellEditor.occupancy(tree));
        assertTrue(tree.isLeaf() && tree.isRemoved());
    }

    @Test
    void addingAnotherMaterialMixesTheBlock() {
        final OctreeNode tree = OctreeCellEditor.full(STONE);
        OctreeCellEditor.apply(tree, edit(16, BlockCellEdit.Operation.ADD, OAK,
            CellVolume.localIndex(16, 3, 4, 5)));
        OctreeCellEditor.canonicalize(tree);

        assertEquals(OctreeCellEditor.Occupancy.PARTIAL, OctreeCellEditor.occupancy(tree));
        assertEquals(OAK, OctreeCellEditor.materialAt(tree, 3, 4, 5));
        assertEquals(STONE, OctreeCellEditor.materialAt(tree, 3, 4, 6));
        assertEquals(STONE, OctreeCellEditor.dominantMaterial(tree));
    }

    @Test
    void coarseEditReplacesFinerDetail() {
        final OctreeNode tree = OctreeCellEditor.full(STONE);
        OctreeCellEditor.apply(tree, edit(16, BlockCellEdit.Operation.CARVE, null,
            CellVolume.localIndex(16, 1, 1, 1)));
        OctreeCellEditor.apply(tree, edit(2, BlockCellEdit.Operation.ADD, OAK, 0));
        OctreeCellEditor.canonicalize(tree);

        for (int x = 0; x < 8; x++) {
            assertEquals(OAK, OctreeCellEditor.materialAt(tree, x, x, x));
        }
        assertEquals(STONE, OctreeCellEditor.materialAt(tree, 8, 8, 8));
        assertEquals(8, OctreeCellEditor.occupiedLeaves(tree));
    }

    @Test
    void paintKeepsTheShape() {
        final OctreeNode tree = OctreeCellEditor.full(STONE);
        OctreeCellEditor.apply(tree, edit(2, BlockCellEdit.Operation.CARVE, null, 0));
        final BitSet all = new BitSet();
        all.set(0, 8);
        OctreeCellEditor.apply(tree,
            BlockCellEdit.single(2, BlockCellEdit.Operation.PAINT, (BlockData) OAK, all));
        OctreeCellEditor.canonicalize(tree);

        assertNull(OctreeCellEditor.materialAt(tree, 0, 0, 0));
        assertEquals(OAK, OctreeCellEditor.materialAt(tree, 15, 0, 0));
        assertEquals(7, OctreeCellEditor.occupiedLeaves(tree));
    }

    @Test
    void noOpEditsReportUnchanged() {
        final OctreeNode tree = OctreeCellEditor.full(STONE);
        assertFalse(OctreeCellEditor.apply(tree,
            edit(8, BlockCellEdit.Operation.ADD, STONE, 17)).changed());

        final OctreeNode empty = OctreeCellEditor.empty(STONE);
        assertFalse(OctreeCellEditor.apply(empty,
            edit(8, BlockCellEdit.Operation.CARVE, null, 17)).changed());
        assertFalse(OctreeCellEditor.apply(empty,
            edit(8, BlockCellEdit.Operation.PAINT, OAK, 17)).changed());
    }

    @Test
    void occupiedPlayerHeadCellsAreNotSplit() {
        final OctreeNode tree = OctreeCellEditor.full(STONE);
        tree.subdivide();
        final OctreeNode head = tree.children()[0];
        head.setPlayerHeadTexture(new PlayerHeadTexture("texture", "signature"));

        final OctreeCellEditor.Result finer = OctreeCellEditor.apply(tree,
            edit(16, BlockCellEdit.Operation.CARVE, null, 0));
        assertFalse(finer.changed());
        assertEquals(1, finer.lockedCells());
        assertSame(head, tree.children()[0]);
        assertTrue(head.isLeaf() && !head.isRemoved());

        // Replacing the whole head cell at its own size is allowed.
        final OctreeCellEditor.Result whole = OctreeCellEditor.apply(tree,
            edit(2, BlockCellEdit.Operation.CARVE, null, 0));
        assertTrue(whole.changed());
        assertTrue(tree.children()[0].isRemoved());
    }

    @Test
    void copyFillsInheritedMaterialsAndIsDetached() {
        final OctreeNode source = new OctreeNode();
        source.subdivide();
        source.children()[3].setBlockData(OAK);
        source.children()[5].remove();

        final OctreeNode copy = OctreeCellEditor.copy(source, STONE);
        assertEquals(STONE, copy.children()[0].blockData());
        assertEquals(OAK, copy.children()[3].blockData());
        assertTrue(copy.children()[5].isRemoved());

        copy.children()[0].remove();
        assertFalse(source.children()[0].isRemoved());
    }

    @Test
    void canonicalizeKeepsRemovedCellsWithDifferentMemories() {
        final OctreeNode tree = new OctreeNode();
        tree.setBlockData(STONE);
        tree.subdivide();
        for (final OctreeNode child : tree.children()) child.remove();
        tree.children()[2].setBlockData(OAK);

        OctreeCellEditor.canonicalize(tree);
        assertTrue(tree.isBranch());
        assertEquals(OctreeCellEditor.Occupancy.EMPTY, OctreeCellEditor.occupancy(tree));

        tree.children()[2].setBlockData(STONE);
        OctreeCellEditor.canonicalize(tree);
        assertTrue(tree.isLeaf() && tree.isRemoved());
    }

    @Test
    void addedHeadCellsKeepTheirTextureExceptAtWholeBlockResolution() {
        final PlayerHeadTexture texture = new PlayerHeadTexture("texture", "signature");
        final BitSet cell = new BitSet();
        cell.set(0);
        final OctreeNode tree = OctreeCellEditor.empty(STONE);
        OctreeCellEditor.apply(tree, new BlockCellEdit(2, List.of(new BlockCellEdit.Layer(
            BlockCellEdit.Operation.ADD, OAK, texture, cell))));
        assertEquals(texture, tree.findLeaf(0, 0, 0).playerHeadTexture());

        final OctreeNode whole = OctreeCellEditor.empty(STONE);
        OctreeCellEditor.apply(whole, new BlockCellEdit(1, List.of(new BlockCellEdit.Layer(
            BlockCellEdit.Operation.ADD, OAK, texture, cell))));
        assertNull(whole.playerHeadTexture(), "the root cannot hold a head texture");
        assertFalse(whole.isRemoved());
    }

    @Test
    void laterLayersWin() {
        final OctreeNode tree = OctreeCellEditor.empty(STONE);
        final BitSet cell = new BitSet();
        cell.set(3);
        final List<BlockCellEdit.Layer> layers = new ArrayList<>();
        layers.add(new BlockCellEdit.Layer(BlockCellEdit.Operation.ADD, STONE, cell));
        layers.add(new BlockCellEdit.Layer(BlockCellEdit.Operation.ADD, OAK, cell));
        OctreeCellEditor.apply(tree, new BlockCellEdit(2, layers));

        assertEquals(OAK, OctreeCellEditor.materialAt(tree,
            CellVolume.localX(2, 3) * 8, CellVolume.localY(2, 3) * 8,
            CellVolume.localZ(2, 3) * 8));
    }

    @Test
    void canonicalizeCollapsesFullyCoveredBranches() {
        // Carving a single cell out of a full block leaves a shape whose
        // remaining volume is mostly whole branches; merging them is what
        // keeps the display entity count down.
        final OctreeNode tree = OctreeCellEditor.full(STONE);
        OctreeCellEditor.apply(tree, edit(16, BlockCellEdit.Operation.CARVE, null, 0));
        OctreeCellEditor.canonicalize(tree);

        assertEquals(28, OctreeCellEditor.occupiedLeaves(tree),
            "one carved cell of 4096 must not leave 4095 separate cells");

        // Refilling it returns the whole block to a single leaf.
        OctreeCellEditor.apply(tree, edit(16, BlockCellEdit.Operation.ADD, STONE, 0));
        OctreeCellEditor.canonicalize(tree);
        assertTrue(tree.isLeaf() && !tree.isRemoved());
        assertEquals(1, OctreeCellEditor.occupiedLeaves(tree));
    }

    @Test
    void canonicalizeLeavesTexturedCellsAlone() {
        // Cells with their own texture coordinate must stay distinct, so a
        // merged branch never loses a per-cell texture.
        final OctreeNode tree = OctreeCellEditor.full(STONE);
        OctreeCellEditor.apply(tree, edit(16, BlockCellEdit.Operation.CARVE, null, 0));
        final List<OctreeNode> leaves = tree.collectLeaves();
        leaves.get(0).setTextureCoord(new ChunkCoord(1, 2, 3));
        final int before = OctreeCellEditor.occupiedLeaves(tree);

        OctreeCellEditor.canonicalize(tree);

        assertEquals(before, OctreeCellEditor.occupiedLeaves(tree));
        assertEquals(new ChunkCoord(1, 2, 3),
            tree.findLeaf(leaves.get(0).minX(), leaves.get(0).minY(),
                leaves.get(0).minZ()).textureCoord());
    }

    private static BlockCellEdit edit(
            final int grid,
            final BlockCellEdit.Operation operation,
            final BlockData material,
            final int index) {
        final BitSet cells = new BitSet();
        cells.set(index);
        return BlockCellEdit.single(grid, operation, (BlockData) material, cells);
    }
}
