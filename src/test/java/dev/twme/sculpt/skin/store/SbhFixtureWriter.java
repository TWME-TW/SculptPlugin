package dev.twme.sculpt.skin.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.DeflaterOutputStream;

import dev.twme.sculpt.assets.model.ModelResolver.VariantRotation;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.ChunkCoord;

/** Creates small SBH fixtures for reader tests; never enters the plugin JAR. */
final class SbhFixtureWriter {

    private SbhFixtureWriter() {}

    static void write(Path output, HeadsStore store, String producer) throws IOException {
        int gridN = store.gridN();
        List<BakeKey> keys = new ArrayList<>(store.listBlocks());
        keys.sort(Comparator.comparing(BakeKey::toString));

        Map<BakeKey, HeadsStore.StoredBlock> blocks = new LinkedHashMap<>();
        TreeSet<String> hashes = new TreeSet<>();
        for (BakeKey key : keys) {
            HeadsStore.StoredBlock block = store.readBlock(key).orElseThrow();
            for (int y = 0; y < gridN; y++) {
                for (int z = 0; z < gridN; z++) {
                    for (int x = 0; x < gridN; x++) {
                        hashes.add(block.chunkHashes().get(new ChunkCoord(x, y, z)));
                    }
                }
            }
            blocks.put(key, block);
        }

        List<String> orderedHashes = List.copyOf(hashes);
        Map<String, Integer> skinIndex = new LinkedHashMap<>();
        List<byte[]> compressedSkins = new ArrayList<>();
        for (int index = 0; index < orderedHashes.size(); index++) {
            String hash = orderedHashes.get(index);
            skinIndex.put(hash, index);
            compressedSkins.add(compress(skinPayload(store.readSkin(hash).orElseThrow())));
        }

        byte[] compressedIndex = compress(indexPayload(
                producer, gridN, blocks, orderedHashes, skinIndex));
        int payloadOffset = 28 + compressedIndex.length + compressedSkins.size() * 4;
        int[] offsets = new int[compressedSkins.size()];
        for (int index = 0; index < compressedSkins.size(); index++) {
            offsets[index] = payloadOffset;
            payloadOffset += 4 + compressedSkins.get(index).length;
        }

        try (OutputStream file = Files.newOutputStream(output)) {
            writeInt(file, SbFormat.MAGIC);
            writeShort(file, SbFormat.FLAG_SECTION2_LAZY);
            writeShort(file, gridN);
            writeInt(file, blocks.size());
            writeInt(file, orderedHashes.size());
            writeInt(file, compressedIndex.length);
            file.write(compressedIndex);
            writeInt(file, orderedHashes.size());
            writeInt(file, offsets.length);
            for (int offset : offsets) writeInt(file, offset);
            for (byte[] skin : compressedSkins) {
                writeInt(file, skin.length);
                file.write(skin);
            }
        }
    }

    private static byte[] indexPayload(
            String producer, int gridN,
            Map<BakeKey, HeadsStore.StoredBlock> blocks,
            List<String> hashes, Map<String, Integer> skinIndex) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, producer);
        for (Map.Entry<BakeKey, HeadsStore.StoredBlock> block : blocks.entrySet()) {
            writeString(out, block.getKey().toString());
            TreeMap<String, VariantRotation> variants = new TreeMap<>(block.getValue().variants());
            writeShort(out, variants.size());
            for (Map.Entry<String, VariantRotation> variant : variants.entrySet()) {
                writeString(out, variant.getKey());
                writeShort(out, variant.getValue().xDeg());
                writeShort(out, variant.getValue().yDeg());
            }
            writeInt(out, gridN * gridN * gridN);
            for (int y = 0; y < gridN; y++) {
                for (int z = 0; z < gridN; z++) {
                    for (int x = 0; x < gridN; x++) {
                        writeInt(out, skinIndex.get(block.getValue().chunkHashes()
                                .get(new ChunkCoord(x, y, z))));
                    }
                }
            }
        }
        for (String hash : hashes) out.write(HexFormat.of().parseHex(hash));
        return out.toByteArray();
    }

    private static byte[] skinPayload(HeadsStore.StoredSkin skin) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean hasUuid = skin.mineskinUuid() != null;
        out.write(hasUuid ? SbFormat.SKIN_FLAG_HAS_UUID : 0);
        out.write(0);
        if (hasUuid) writeString(out, skin.mineskinUuid());
        writeString(out, skin.value());
        writeString(out, skin.signature());
        return out.toByteArray();
    }

    private static byte[] compress(byte[] value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(value);
        }
        return out.toByteArray();
    }

    private static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeShort(out, bytes.length);
        out.write(bytes);
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
