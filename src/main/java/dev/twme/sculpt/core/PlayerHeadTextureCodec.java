package dev.twme.sculpt.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deduplicated binary persistence for held player-head textures on leaves. */
public final class PlayerHeadTextureCodec {

    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 4_096;
    private static final int MAX_BYTES = 8_388_608;

    private PlayerHeadTextureCodec() {}

    public static byte[] encode(final OctreeNode root) {
        final List<OctreeNode> leaves = new ArrayList<>();
        root.collectAllLeaves(leaves);
        final Map<PlayerHeadTexture, Integer> textureIndexes = new LinkedHashMap<>();
        final List<OctreeNode> texturedLeaves = new ArrayList<>();
        long size = 1 + 2 + 2;
        for (final OctreeNode leaf : leaves) {
            final PlayerHeadTexture texture = leaf.playerHeadTexture();
            if (texture == null) continue;
            texturedLeaves.add(leaf);
            if (texturedLeaves.size() > MAX_ENTRIES) {
                throw new IllegalStateException("Too many player-head texture assignments");
            }
            if (!textureIndexes.containsKey(texture)) {
                textureIndexes.put(texture, textureIndexes.size());
                size += 2L + utf8Length(texture.value())
                    + 2L + utf8Length(texture.signature());
            }
            size += 1L + leaf.depth() + 2L;
        }
        if (texturedLeaves.isEmpty()) return new byte[0];
        if (size > MAX_BYTES) {
            throw new IllegalStateException("Player-head texture data exceeds size limit");
        }

        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) size);
            final DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(VERSION);
            output.writeShort(textureIndexes.size());
            for (final PlayerHeadTexture texture : textureIndexes.keySet()) {
                writeUtf8(output, texture.value());
                writeUtf8(output, texture.signature());
            }
            output.writeShort(texturedLeaves.size());
            for (final OctreeNode leaf : texturedLeaves) {
                output.writeByte(leaf.depth());
                final int[] path = octantPath(leaf);
                for (final int octant : path) output.writeByte(octant);
                output.writeShort(textureIndexes.get(leaf.playerHeadTexture()));
            }
            output.flush();
            return bytes.toByteArray();
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to encode player-head textures", exception);
        }
    }

    public static void apply(final byte[] encoded, final OctreeNode root) {
        if (encoded == null || encoded.length == 0) return;
        if (encoded.length > MAX_BYTES) {
            throw new IllegalArgumentException("Player-head texture data exceeds size limit");
        }

        final List<PlayerHeadTexture> textures = new ArrayList<>();
        final List<Assignment> assignments = new ArrayList<>();
        try {
            final DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded));
            if (input.readUnsignedByte() != VERSION) {
                throw new IllegalArgumentException("Unsupported player-head texture data version");
            }
            final int textureCount = input.readUnsignedShort();
            if (textureCount == 0 || textureCount > MAX_ENTRIES) {
                throw new IllegalArgumentException("Invalid player-head texture count");
            }
            for (int index = 0; index < textureCount; index++) {
                textures.add(new PlayerHeadTexture(
                    readUtf8(input, PlayerHeadTexture.MAX_VALUE_BYTES, false),
                    readUtf8(input, PlayerHeadTexture.MAX_SIGNATURE_BYTES, true)));
            }

            final int assignmentCount = input.readUnsignedShort();
            if (assignmentCount == 0 || assignmentCount > MAX_ENTRIES) {
                throw new IllegalArgumentException("Invalid player-head assignment count");
            }
            final Set<String> paths = new HashSet<>();
            for (int index = 0; index < assignmentCount; index++) {
                final int depth = input.readUnsignedByte();
                if (depth < 1 || depth > 4) {
                    throw new IllegalArgumentException("Invalid player-head leaf depth");
                }
                final int[] path = new int[depth];
                final StringBuilder pathKey = new StringBuilder(depth * 2 - 1);
                for (int pathIndex = 0; pathIndex < depth; pathIndex++) {
                    path[pathIndex] = input.readUnsignedByte();
                    if (path[pathIndex] > 7) {
                        throw new IllegalArgumentException("Invalid player-head leaf path");
                    }
                    if (pathIndex > 0) pathKey.append('.');
                    pathKey.append(path[pathIndex]);
                }
                if (!paths.add(pathKey.toString())) {
                    throw new IllegalArgumentException("Duplicate player-head leaf path");
                }
                final int textureIndex = input.readUnsignedShort();
                if (textureIndex >= textures.size()) {
                    throw new IllegalArgumentException("Invalid player-head texture index");
                }
                assignments.add(new Assignment(path, textures.get(textureIndex)));
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Trailing player-head texture data");
            }
        } catch (final IOException exception) {
            throw new IllegalArgumentException("Truncated player-head texture data", exception);
        }

        final List<OctreeNode> targets = new ArrayList<>(assignments.size());
        for (final Assignment assignment : assignments) {
            OctreeNode node = root;
            for (final int octant : assignment.path()) {
                if (!node.isBranch()) {
                    throw new IllegalArgumentException(
                        "Player-head path does not resolve to a leaf");
                }
                node = node.children()[octant];
            }
            if (!node.isLeaf()) {
                throw new IllegalArgumentException(
                    "Player-head path does not identify a leaf");
            }
            targets.add(node);
        }
        for (int index = 0; index < assignments.size(); index++) {
            targets.get(index).setPlayerHeadTexture(assignments.get(index).texture());
        }
    }

    private static int[] octantPath(final OctreeNode leaf) {
        final int[] path = new int[leaf.depth()];
        OctreeNode node = leaf;
        for (int index = path.length - 1; index >= 0; index--) {
            path[index] = node.octantIndex();
            node = node.parent();
        }
        return path;
    }

    private static int utf8Length(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void writeUtf8(final DataOutputStream output, final String value)
            throws IOException {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readUtf8(
            final DataInputStream input, final int maximum,
            final boolean emptyAllowed) throws IOException {
        final int length = input.readUnsignedShort();
        if ((!emptyAllowed && length == 0) || length > maximum) {
            throw new IllegalArgumentException("Invalid player-head texture field length");
        }
        final byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (final CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid player-head texture UTF-8", exception);
        }
    }

    private record Assignment(int[] path, PlayerHeadTexture texture) {}
}
