package dev.twme.sculpt.blueprint;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.PlayerHeadTexture;

class PasteEngineTest {

    @Test
    void adhesiveMergeNeverSplitsAtomicHeadIntoFineDestinationLeaves() {
        final OctreeNode base = fullDepthOneTree();
        base.children()[0].subdivide();
        for (int i = 1; i < 8; i++) base.children()[0].children()[i].remove();
        final OctreeNode source = fullDepthOneTree(1, 2, 3, 4, 5, 6, 7);
        final PlayerHeadTexture texture = new PlayerHeadTexture("texture", "");
        source.children()[0].setPlayerHeadTexture(texture);

        final OctreeNode result = PasteEngine.mergeTrees(base, source, false, false);

        assertTrue(result.children()[0].isBranch());
        assertEquals(1, result.children()[0].collectLeaves().size());
        assertTrue(result.children()[0].collectLeaves().stream()
            .noneMatch(leaf -> leaf.playerHeadTexture() != null));
    }

    @Test
    void adhesiveMergePlacesOneAtomicHeadWhenFineRegionIsEntirelyEmpty() {
        final OctreeNode base = fullDepthOneTree();
        base.children()[0].subdivide();
        for (final OctreeNode child : base.children()[0].children()) child.remove();
        final OctreeNode source = fullDepthOneTree(1, 2, 3, 4, 5, 6, 7);
        final PlayerHeadTexture texture = new PlayerHeadTexture("texture", "signature");
        source.children()[0].setPlayerHeadTexture(texture);

        final OctreeNode result = PasteEngine.mergeTrees(base, source, false, false);

        assertTrue(result.children()[0].isLeaf());
        assertFalse(result.children()[0].isRemoved());
        assertEquals(texture, result.children()[0].playerHeadTexture());
    }

    @Test
    void overwriteTrueAndPasteAirTrueReplaceExistingCells() {
        OctreeNode base = fullDepthOneTree(0);
        OctreeNode source = fullDepthOneTree(1);

        OctreeNode result = PasteEngine.mergeTrees(base, source, true, true);

        assertFalse(result.children()[0].isRemoved());
        assertTrue(result.children()[1].isRemoved());
    }

    @Test
    void pasteAirFalseDoesNotRemoveExistingCells() {
        OctreeNode base = fullDepthOneTree(0);
        OctreeNode source = fullDepthOneTree(1);

        OctreeNode result = PasteEngine.mergeTrees(base, source, false, true);

        assertFalse(result.children()[0].isRemoved());
        assertFalse(result.children()[1].isRemoved());
    }

    @Test
    void overwriteCellsFalseOnlyFillsExistingHoles() {
        OctreeNode base = fullDepthOneTree(0);
        OctreeNode source = fullDepthOneTree(1);

        OctreeNode result = PasteEngine.mergeTrees(base, source, true, false);

        assertFalse(result.children()[0].isRemoved(), "source content should fill a hole");
        assertFalse(result.children()[1].isRemoved(), "source air must not erase existing content");
    }

    @Test
    void mergingFineSourceIntoRemovedCoarseCellPreservesVolume() {
        OctreeNode base = new OctreeNode();
        base.remove();
        OctreeNode source = fullDepthOneTree(1, 2, 3, 4, 5, 6, 7);

        OctreeNode result = PasteEngine.mergeTrees(base, source, false, true);

        assertTrue(result.isBranch());
        assertFalse(result.children()[0].isRemoved());
        for (int i = 1; i < 8; i++) assertTrue(result.children()[i].isRemoved());
        assertEquals(1, result.collectLeaves().size());
    }

    @Test
    void overwritingFineTreeWithCoarseLeafCoarsensResult() {
        OctreeNode base = fullDepthOneTree(0, 1, 2);
        OctreeNode source = new OctreeNode();

        OctreeNode result = PasteEngine.mergeTrees(base, source, true, true);

        assertTrue(result.isLeaf());
        assertFalse(result.isRemoved());
    }

    @Test
    void rotationDegreesMapBlueprintFrontToCardinalFacing() {
        assertEquals(0, PasteEngine.degreesForFacing(org.bukkit.block.BlockFace.SOUTH));
        assertEquals(90, PasteEngine.degreesForFacing(org.bukkit.block.BlockFace.WEST));
        assertEquals(180, PasteEngine.degreesForFacing(org.bukkit.block.BlockFace.NORTH));
        assertEquals(270, PasteEngine.degreesForFacing(org.bukkit.block.BlockFace.EAST));
    }

    @Test
    void relativeRotationKeepsTheSamePlayerSideUnchanged() {
        for (org.bukkit.block.BlockFace facing : new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.WEST}) {
            assertEquals(0, PasteEngine.relativeDegrees(facing, facing));
        }
        assertEquals(0, PasteEngine.relativeDegrees(null,
            org.bukkit.block.BlockFace.NORTH),
            "legacy blueprints without a reference must keep their saved orientation");
    }

    @Test
    void playerPositionResolvesTowardAllFourSidesOfTarget() {
        assertEquals(org.bukkit.block.BlockFace.SOUTH,
            PasteEngine.facingToward(0.5, -2, 0.5, 0.5, null));
        assertEquals(org.bukkit.block.BlockFace.NORTH,
            PasteEngine.facingToward(0.5, 3, 0.5, 0.5, null));
        assertEquals(org.bukkit.block.BlockFace.EAST,
            PasteEngine.facingToward(-2, 0.5, 0.5, 0.5, null));
        assertEquals(org.bukkit.block.BlockFace.WEST,
            PasteEngine.facingToward(3, 0.5, 0.5, 0.5, null));
    }

    @Test
    void relativeRotationTurnsBlueprintWhenPlayerMovesAroundTarget() {
        assertEquals(180, PasteEngine.relativeDegrees(
            org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH));
        assertEquals(180, PasteEngine.relativeDegrees(
            org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST));
        assertEquals(90, PasteEngine.relativeDegrees(
            org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.SOUTH));
        assertEquals(270, PasteEngine.relativeDegrees(
            org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST));
    }

    @Test
    void cuboidCoordinatesRotateWithinTransformedBounds() {
        assertEquals(new PasteEngine.BlockOffset(2, 1, 0),
            PasteEngine.transformBlockPosition(0, 1, 0, 2, 3, 3, 90, null));
        assertEquals(new PasteEngine.BlockOffset(1, 1, 2),
            PasteEngine.transformBlockPosition(0, 1, 0, 2, 3, 3, 180, null));
        assertEquals(new PasteEngine.BlockOffset(0, 1, 1),
            PasteEngine.transformBlockPosition(0, 1, 0, 2, 3, 3, 270, null));
        assertEquals(new PasteEngine.BlockOffset(0, 1, 0),
            PasteEngine.transformBlockPosition(0, 1, 0, 2, 3, 3, 0, null));
    }

    @Test
    void cuboidFlipUsesDimensionsAfterRotation() {
        assertEquals(new PasteEngine.BlockOffset(0, 1, 0),
            PasteEngine.transformBlockPosition(0, 1, 0, 2, 3, 3, 90, "x"));
        assertEquals(new PasteEngine.BlockOffset(2, 1, 1),
            PasteEngine.transformBlockPosition(0, 1, 0, 2, 3, 3, 90, "z"));
        assertEquals(new PasteEngine.BlockOffset(2, 1, 0),
            PasteEngine.transformBlockPosition(0, 1, 0, 2, 3, 3, 90, "y"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
            new int[]{3, 3, 2}, PasteEngine.transformedDimensions(2, 3, 3, 90));
    }

    @Test
    void rotatedOctreePreservesMixedResolutionLeafDepthAndVolume() throws Exception {
        OctreeNode root = new OctreeNode();
        root.subdivide();
        OctreeNode coarseLeaf = root.children()[0];

        Map<String, int[]> rotatedCoords = new HashMap<>();
        Map<String, int[]> sourceCoords = Map.of(coarseLeaf.pathAsString(), new int[]{0, 0, 0});
        PasteSettings settings = new PasteSettings(
            true, true, true, false, PasteSettings.RotateMode.FACE, 90, null);

        OctreeNode rotated = invokeBuildRotatedOctree(root, 16, settings, rotatedCoords, sourceCoords);

        OctreeNode rotatedLeaf = OctreeNode.fromPath(rotated, "4");
        assertNotNull(rotatedLeaf);
        assertTrue(rotatedLeaf.isLeaf());
        assertEquals(1, rotatedLeaf.depth());
        assertEquals(8, rotatedLeaf.side());
        assertEquals(8, rotatedLeaf.minX());
        assertEquals(0, rotatedLeaf.minY());
        assertEquals(0, rotatedLeaf.minZ());
        assertEquals(8, rotated.collectLeaves().size());
        assertTrue(rotatedCoords.containsKey("4"));
    }

    private static OctreeNode invokeBuildRotatedOctree(
            OctreeNode source, int gridN, PasteSettings settings,
            Map<String, int[]> rotatedCoords, Map<String, int[]> sourceCoords) throws Exception {
        PasteEngine engine = new PasteEngine(null);
        Method method = PasteEngine.class.getDeclaredMethod(
            "buildRotatedOctree", OctreeNode.class, int.class, PasteSettings.class, Map.class, Map.class);
        method.setAccessible(true);
        return (OctreeNode) method.invoke(engine, source, gridN, settings, rotatedCoords, sourceCoords);
    }

    private static OctreeNode fullDepthOneTree(int... removedChildren) {
        OctreeNode root = new OctreeNode();
        root.subdivide();
        for (int child : removedChildren) root.children()[child].remove();
        return root;
    }
}
