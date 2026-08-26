package dev.twme.sculpt.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class PlayerHeadTextureCodecTest {

    @Test
    void roundTripPreservesAssignmentsAndDeduplicatesProfiles() {
        final PlayerHeadTexture shared = new PlayerHeadTexture(
            "shared-texture-value", "shared-signature");
        final PlayerHeadTexture unique = new PlayerHeadTexture(
            "unique-texture-value", "");
        final OctreeNode source = depthTwoTree();
        source.children()[0].children()[1].setPlayerHeadTexture(shared);
        source.children()[0].children()[2].setPlayerHeadTexture(shared);
        source.children()[3].children()[7].setPlayerHeadTexture(unique);

        final byte[] encoded = PlayerHeadTextureCodec.encode(source);
        final OctreeNode restored = depthTwoTree();
        PlayerHeadTextureCodec.apply(encoded, restored);

        assertEquals(shared,
            restored.children()[0].children()[1].playerHeadTexture());
        assertEquals(shared,
            restored.children()[0].children()[2].playerHeadTexture());
        assertEquals(unique,
            restored.children()[3].children()[7].playerHeadTexture());
        assertNull(restored.children()[7].children()[7].playerHeadTexture());
        assertEquals(1, occurrences(
            encoded, shared.value().getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, occurrences(
            encoded, shared.signature().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void emptyTreeUsesAnEmptyPayload() {
        assertArrayEquals(new byte[0],
            PlayerHeadTextureCodec.encode(new OctreeNode()));
        PlayerHeadTextureCodec.apply(null, new OctreeNode());
        PlayerHeadTextureCodec.apply(new byte[0], new OctreeNode());
    }

    @Test
    void invalidDataDoesNotPartiallyApplyAssignments() {
        final OctreeNode source = depthTwoTree();
        source.children()[0].children()[0].setPlayerHeadTexture(
            new PlayerHeadTexture("texture", "signature"));
        final byte[] valid = PlayerHeadTextureCodec.encode(source);
        final byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
        final OctreeNode target = depthTwoTree();

        assertThrows(IllegalArgumentException.class,
            () -> PlayerHeadTextureCodec.apply(truncated, target));
        assertNull(target.children()[0].children()[0].playerHeadTexture());
    }

    @Test
    void assignmentPathMustResolveAgainstTheCurrentTree() {
        final OctreeNode source = depthTwoTree();
        source.children()[0].children()[0].setPlayerHeadTexture(
            new PlayerHeadTexture("texture", ""));
        final byte[] encoded = PlayerHeadTextureCodec.encode(source);

        assertThrows(IllegalArgumentException.class,
            () -> PlayerHeadTextureCodec.apply(encoded, new OctreeNode()));
    }

    private static OctreeNode depthTwoTree() {
        final OctreeNode root = new OctreeNode();
        root.subdivide();
        for (final OctreeNode child : root.children()) child.subdivide();
        return root;
    }

    private static int occurrences(final byte[] haystack, final byte[] needle) {
        int count = 0;
        outer:
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) continue outer;
            }
            count++;
        }
        return count;
    }
}
