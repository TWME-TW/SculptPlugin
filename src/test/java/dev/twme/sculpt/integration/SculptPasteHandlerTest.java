package dev.twme.sculpt.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import org.junit.jupiter.api.Test;

class SculptPasteHandlerTest {

    @Test
    void recognizesWorldEditClipboardCopyCommands() {
        assertTrue(SculptPasteHandler.isClipboardCopyCommand("//copy -e"));
        assertTrue(SculptPasteHandler.isClipboardCopyCommand("//cut -e"));
        assertTrue(SculptPasteHandler.isClipboardCopyCommand("//lazycopy -e"));
        assertTrue(SculptPasteHandler.isClipboardCopyCommand("//lazycut -e"));
    }

    @Test
    void recognizesNamespacedClipboardCopyCommands() {
        assertTrue(SculptPasteHandler.isClipboardCopyCommand(
                "/worldedit:/copy -e"));
        assertTrue(SculptPasteHandler.isClipboardCopyCommand(
                "/fastasyncworldedit:/lazycopy -e"));
    }

    @Test
    void ignoresUnrelatedCommandsAndCopyLikeArguments() {
        assertFalse(SculptPasteHandler.isClipboardCopyCommand("//paste -e"));
        assertFalse(SculptPasteHandler.isClipboardCopyCommand("/sculpt copy"));
        assertFalse(SculptPasteHandler.isClipboardCopyCommand("/say //copy -e"));
        assertFalse(SculptPasteHandler.isClipboardCopyCommand("  "));
        assertFalse(SculptPasteHandler.isClipboardCopyCommand(null));
    }

    @Test
    void detectsClipboardEntityFlagsWithoutConfusingOtherOptions() {
        assertTrue(SculptPasteHandler.copiesEntities("//copy -e"));
        assertTrue(SculptPasteHandler.copiesEntities("//copy -be -m stone"));
        assertFalse(SculptPasteHandler.copiesEntities("//copy"));
        assertFalse(SculptPasteHandler.copiesEntities("//copy -m stone"));
        assertFalse(SculptPasteHandler.copiesEntities(null));
    }

    @Test
    void mapsClipboardRootToIdentityPastePosition() {
        BlockVector3 result = SculptPasteHandler.transformClipboardEntityPosition(
                Vector3.at(12.5, 64.5, 20.5),
                BlockVector3.at(10, 64, 20),
                BlockVector3.at(30, 70, 40),
                new AffineTransform());

        assertEquals(BlockVector3.at(32, 70, 40), result);
    }

    @Test
    void mapsClipboardRootUsingClipboardRotation() {
        BlockVector3 result = SculptPasteHandler.transformClipboardEntityPosition(
                Vector3.at(12.5, 64.5, 20.5),
                BlockVector3.at(10, 64, 20),
                BlockVector3.at(30, 70, 40),
                new AffineTransform().rotateY(90));

        assertEquals(BlockVector3.at(30, 70, 38), result);
    }

    @Test
    void groupsHighResolutionPasteTargetsIntoOneVerificationPerChunk() {
        Set<BlockVector3> positions = new LinkedHashSet<>();
        for (int y = 0; y < 256; y++) {
            positions.add(BlockVector3.at(3, y, 7));
        }

        Map<SculptPasteHandler.ChunkKey, Set<SculptPasteHandler.BlockPosition>> grouped =
                SculptPasteHandler.groupPositionsByChunk("world", positions);

        assertEquals(1, grouped.size());
        assertEquals(256, grouped.values().iterator().next().size());
    }

    @Test
    void groupsPasteTargetsUsingMinecraftNegativeChunkCoordinates() {
        Set<BlockVector3> positions = Set.of(
                BlockVector3.at(-1, 64, -1),
                BlockVector3.at(-16, 64, -16),
                BlockVector3.at(-17, 64, -17),
                BlockVector3.at(0, 64, 0));

        Map<SculptPasteHandler.ChunkKey, Set<SculptPasteHandler.BlockPosition>> grouped =
                SculptPasteHandler.groupPositionsByChunk("world", positions);

        assertEquals(3, grouped.size());
        assertEquals(2, grouped.get(
                new SculptPasteHandler.ChunkKey("world", -1, -1)).size());
    }
}
