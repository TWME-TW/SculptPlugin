package dev.twme.sculpt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OctreeNodeTest {

    @Test
    void playerHeadTextureSurvivesOctreeSerialization() {
        final PlayerHeadTexture texture = new PlayerHeadTexture(
            "eyJ0ZXh0dXJlcyI6e319", "signed-value");
        final OctreeNode root = new OctreeNode();
        root.subdivide();
        root.children()[3].setPlayerHeadTexture(texture);

        final OctreeNode restored = OctreeNode.deserialize(root.serialize(), 4);

        assertEquals(texture, restored.children()[3].playerHeadTexture());
    }

    @Test
    void deserializeRejectsTruncatedPlayerHeadTexture() {
        assertThrows(IllegalArgumentException.class,
            () -> OctreeNode.deserialize(new byte[]{
                (byte) 0x88, 0, 1, 'x', 0
            }, 0));
    }

    @Test
    void playerHeadTextureRequiresAnIndivisibleNonRootLeaf() {
        final OctreeNode root = new OctreeNode();
        final PlayerHeadTexture texture = new PlayerHeadTexture("texture", "");

        assertThrows(IllegalArgumentException.class,
            () -> root.setPlayerHeadTexture(texture));
        root.subdivide();
        final OctreeNode leaf = root.children()[0];
        leaf.setPlayerHeadTexture(texture);

        assertThrows(IllegalStateException.class, leaf::subdivide);
        assertTrue(leaf.isLeaf());
        assertEquals(texture, leaf.playerHeadTexture());
    }

    @Test
    void serializerRejectsTreesAboveBlueprintSizeLimit() {
        final OctreeNode root = new OctreeNode();
        subdivideToDepth(root, 4);
        final PlayerHeadTexture texture = new PlayerHeadTexture(
            "x".repeat(1_024), "");
        for (final OctreeNode leaf : root.collectLeaves()) {
            leaf.setPlayerHeadTexture(texture);
        }

        assertThrows(IllegalStateException.class, root::serialize);
    }

    private static void subdivideToDepth(final OctreeNode node, final int depth) {
        if (node.depth() >= depth) return;
        node.subdivide();
        for (final OctreeNode child : node.children()) {
            subdivideToDepth(child, depth);
        }
    }

    @Test
    void deserializeRejectsTrailingData() {
        assertThrows(IllegalArgumentException.class,
            () -> OctreeNode.deserialize(new byte[]{(byte) 0x80, 0}, 0));
    }

    @Test
    void deserializeRejectsPlayerHeadTextureAtGridOne() {
        assertThrows(IllegalArgumentException.class,
            () -> OctreeNode.deserialize(new byte[]{
                (byte) 0x88, 0, 1, 'x', 0, 0
            }, 0));
    }

    @Test
    void deserializeRejectsBranchAtMaximumDepth() {
        assertThrows(IllegalArgumentException.class,
            () -> OctreeNode.deserialize(new byte[]{0}, 0));
    }

    @Test
    void deserializeRejectsReservedHeaderFlags() {
        assertThrows(IllegalArgumentException.class,
            () -> OctreeNode.deserialize(new byte[]{(byte) 0x81}, 0));
    }

    @Test
    void branchWithOnlySomeRemovedDescendantsIsNotAllRemoved() {
        OctreeNode root = new OctreeNode();
        root.subdivide();
        OctreeNode branch = root.children()[0];
        branch.subdivide();

        branch.children()[0].remove();

        assertTrue(branch.hasAnyRemoved());
        assertFalse(branch.allRemoved());
        assertTrue(root.hasAnyRemoved());
        assertFalse(root.allRemoved());
    }

    @Test
    void branchWithPartiallyRemovedChildBranchesIsNotAllRemoved() {
        OctreeNode root = new OctreeNode();
        root.subdivide();
        OctreeNode branch = root.children()[0];
        branch.subdivide();

        for (OctreeNode childBranch : branch.children()) {
            childBranch.subdivide();
            childBranch.children()[0].remove();
        }

        assertTrue(branch.hasAnyRemoved());
        assertFalse(branch.allRemoved());
    }
}
