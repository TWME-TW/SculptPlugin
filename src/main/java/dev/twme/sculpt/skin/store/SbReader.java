package dev.twme.sculpt.skin.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.InflaterInputStream;

import dev.twme.sculpt.assets.model.ModelResolver.VariantRotation;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;

/**
 * Reads the {@code .sbh} (Sculpt Baked Heads) binary format.
 *
 * <p>The reader eagerly parses Section 1 (block index + skin hash array)
 * into heap memory — this is cheap (~tens of MB even for gridN=16).
 *
 * <p>Two format versions are supported:
 * <ul>
 *   <li><b>V1</b> ({@link SbFormat#FLAG_SECTION2_COMPRESSED}): Section 2 is
 *       a single monolithic DEFLATE block. The reader decompresses the entire
 *       section eagerly at construction time.</li>
 *   <li><b>V2</b> ({@link SbFormat#FLAG_SECTION2_LAZY}): Section 2 uses
 *       per-skin DEFLATE with a file-offset table. Skins are decompressed
 *       lazily on demand. When loaded via {@link #fromFile(Path)} the raw
 *       file data never resides on the Java heap — a {@link FileChannel} is
 *       kept open for positional reads. Call {@link #close()} to release the
 *       channel.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * try (SbReader reader = SbReader.fromFile(path)) {
 *     int[] skinIndices = reader.chunkSkinIndices(bakeKey);
 *     String hexHash = reader.skinHexHash(skinIndices[0]);
 *     SbReader.SkinPayload payload = reader.findSkin(hexHash).get();
 * }
 * }</pre>
 */
public final class SbReader implements AutoCloseable {

    private static final String MALFORMED_MESSAGE = "Malformed SBH file";

    // ── Common: Section 1 data ───────────────────────────────────────────

    private final int gridN;
    private final String producer;
    private final List<BakeKey> bakeKeys;
    private final Map<BakeKey, BlockInfo> blocks;
    private final String[] skinHexHashes;
    private final Map<String, Integer> skinHexIndex;

    // ── Format selection ─────────────────────────────────────────────────

    private final boolean v2LazyMode;

    // ── V1: eagerly decompressed monolithic Section 2 ────────────────────

    private final byte[] section2Data;
    private final int[] section2Offsets;   // into section2Data

    // ── V2: lazy per-skin DEFLATE ────────────────────────────────────────

    private final int[] v2SkinOffsets;     // absolute file offsets
    private final FileChannel fileChannel; // non-null for fromFile lazy mode
    private final byte[] rawFileData;      // non-null for fromBytes lazy mode

    // ── Internal records ──────────────────────────────────────────────────

    private record BlockInfo(Map<String, VariantRotation> variants,
                             int[] chunkSkinIndices) {}

    /**
     * The decoded MineSkin payload for one skin.
     */
    public record SkinPayload(String value, String signature, String mineskinUuid) {}

    // ========================================================================
    //  Construction — V1 (eager, monolithic)
    // ========================================================================

    private SbReader(final ByteBuffer buf) throws IOException {
        this.v2LazyMode = false;
        this.fileChannel = null;
        this.rawFileData = null;
        this.v2SkinOffsets = null;

        buf.order(ByteOrder.LITTLE_ENDIAN);

        // ── Header ────────────────────────────────────────────────────────
        final int magic = buf.getInt();
        if (magic != SbFormat.MAGIC) {
            throw new IOException(MALFORMED_MESSAGE + ": bad magic 0x"
                    + Integer.toHexString(magic));
        }
        final int flags = buf.getShort() & 0xFFFF;
        final boolean section2Compressed = (flags & SbFormat.FLAG_SECTION2_COMPRESSED) != 0;
        this.gridN = buf.getShort() & 0xFFFF;
        if (this.gridN < 1 || this.gridN > 16) {
            throw new IOException(MALFORMED_MESSAGE + ": invalid gridN=" + this.gridN);
        }
        final int blockCount = buf.getInt();
        final int skinCount = buf.getInt();

        // ── Section 1: Index ──────────────────────────────────────────────
        final int idxCompLen = buf.getInt();
        if (idxCompLen <= 0) {
            throw new IOException(MALFORMED_MESSAGE + ": empty index section");
        }
        final byte[] idxCompressed = new byte[idxCompLen];
        buf.get(idxCompressed);
        final byte[] idxRaw = decompress(idxCompressed);
        final ByteBuffer idxBuf = ByteBuffer.wrap(idxRaw).order(ByteOrder.LITTLE_ENDIAN);

        // Manifest
        this.producer = readUTF16(idxBuf);

        // Block entries
        final Section1Data s1 = parseBlockEntries(idxBuf, blockCount, skinCount, gridN);
        this.bakeKeys = s1.bakeKeys;
        this.blocks = s1.blocks;
        this.skinHexHashes = s1.skinHexHashes;
        this.skinHexIndex = s1.skinHexIndex;

        // ── Section 2 (V1): monolithic ────────────────────────────────────
        final int payloadCount = buf.getInt();
        if (payloadCount != skinCount) {
            throw new IOException(MALFORMED_MESSAGE + ": payloadCount=" + payloadCount
                    + " != skinCount=" + skinCount);
        }
        final int decompLen = buf.getInt();
        final int offsetCount = buf.getInt();
        if (offsetCount != skinCount + 1) {
            throw new IOException(MALFORMED_MESSAGE + ": offsetCount=" + offsetCount
                    + " != skinCount+1=" + (skinCount + 1));
        }
        final int[] offsets = new int[offsetCount];
        for (int i = 0; i < offsetCount; i++) {
            offsets[i] = buf.getInt();
        }
        this.section2Offsets = offsets;

        final int compressedLen = buf.getInt();
        if (compressedLen < 0 || buf.remaining() < compressedLen) {
            throw new IOException(MALFORMED_MESSAGE + ": truncated section 2"
                    + " (expected=" + compressedLen
                    + " actual=" + buf.remaining()
                    + " at offset=" + (buf.position() - 4) + ")"
                    + " — file may be incomplete or corrupted");
        }

        final byte[] section2Raw = new byte[compressedLen > 0 ? compressedLen : decompLen];
        buf.get(section2Raw);

        if (section2Compressed && compressedLen > 0) {
            this.section2Data = decompress(section2Raw);
        } else {
            this.section2Data = section2Raw;
        }
    }

    // ========================================================================
    //  Construction — V2 (lazy, per-skin DEFLATE)
    // ========================================================================

    /** V2 from FileChannel (file-backed lazy loading — raw data never on heap). */
    private SbReader(
            final FileChannel channel,
            final int gridN,
            final Section1Data s1,
            final int[] v2SkinOffsets
    ) {
        this.v2LazyMode = true;
        this.fileChannel = channel;
        this.rawFileData = null;
        this.gridN = gridN;
        this.producer = s1.producer;
        this.bakeKeys = s1.bakeKeys;
        this.blocks = s1.blocks;
        this.skinHexHashes = s1.skinHexHashes;
        this.skinHexIndex = s1.skinHexIndex;
        this.v2SkinOffsets = v2SkinOffsets;
        this.section2Data = null;
        this.section2Offsets = null;
    }

    /** V2 from byte array (heap-backed lazy loading). */
    private SbReader(
            final byte[] rawFileData,
            final int gridN,
            final Section1Data s1,
            final int[] v2SkinOffsets
    ) {
        this.v2LazyMode = true;
        this.fileChannel = null;
        this.rawFileData = rawFileData;
        this.gridN = gridN;
        this.producer = s1.producer;
        this.bakeKeys = s1.bakeKeys;
        this.blocks = s1.blocks;
        this.skinHexHashes = s1.skinHexHashes;
        this.skinHexIndex = s1.skinHexIndex;
        this.v2SkinOffsets = v2SkinOffsets;
        this.section2Data = null;
        this.section2Offsets = null;
    }

    // ========================================================================
    //  Shared Section 1 parser
    // ========================================================================

    private record Section1Data(
            String producer,
            List<BakeKey> bakeKeys,
            Map<BakeKey, BlockInfo> blocks,
            String[] skinHexHashes,
            Map<String, Integer> skinHexIndex
    ) {}

    private static Section1Data parseBlockEntries(
            final ByteBuffer idxBuf,
            final int blockCount,
            final int skinCount,
            final int gridN
    ) throws IOException {
        // Manifest
        final String producer = readUTF16(idxBuf);

        // Block entries
        final List<BakeKey> keys = new ArrayList<>(blockCount);
        final Map<BakeKey, BlockInfo> blkMap = new LinkedHashMap<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            final String bakeKeyStr = readUTF16(idxBuf);
            final BakeKey bakeKey;
            try {
                bakeKey = BakeKey.parse(bakeKeyStr);
            } catch (final RuntimeException e) {
                throw new IOException(MALFORMED_MESSAGE + ": bad bakeKey \"" + bakeKeyStr + '"', e);
            }

            final int variantCount = idxBuf.getShort() & 0xFFFF;
            final Map<String, VariantRotation> variants;
            if (variantCount == 0) {
                variants = Collections.emptyMap();
            } else {
                variants = new LinkedHashMap<>(variantCount);
                for (int v = 0; v < variantCount; v++) {
                    final String vk = readUTF16(idxBuf);
                    final int xDeg = idxBuf.getShort();
                    final int yDeg = idxBuf.getShort();
                    variants.put(vk, new VariantRotation(xDeg, yDeg));
                }
            }

            final int chunkCount = idxBuf.getInt();
            if (chunkCount != gridN * gridN * gridN) {
                throw new IOException(MALFORMED_MESSAGE + ": block \"" + bakeKeyStr
                        + "\" has " + chunkCount + " chunks, expected " + (gridN * gridN * gridN));
            }
            final int[] skinIndices = new int[chunkCount];
            for (int c = 0; c < chunkCount; c++) {
                skinIndices[c] = idxBuf.getInt();
            }

            keys.add(bakeKey);
            blkMap.put(bakeKey, new BlockInfo(variants, skinIndices));
        }

        // Skin hash array
        if (idxBuf.capacity() - idxBuf.position() < skinCount * 32L) {
            throw new IOException(MALFORMED_MESSAGE + ": truncated skin hash array");
        }
        final String[] hexHashes = new String[skinCount];
        final Map<String, Integer> hexIdx = HashMap.newHashMap(skinCount);
        for (int i = 0; i < skinCount; i++) {
            final byte[] hash = new byte[32];
            idxBuf.get(hash);
            final String hex = HexFormat.of().formatHex(hash);
            hexHashes[i] = hex;
            hexIdx.put(hex, i);
        }

        return new Section1Data(
                producer,
                Collections.unmodifiableList(keys),
                Collections.unmodifiableMap(blkMap),
                hexHashes,
                hexIdx
        );
    }

    // ========================================================================
    //  Factory methods
    // ========================================================================

    /**
     * Load an {@code .sbh} file from the filesystem.
     *
     * <p>For V2 format, the file channel is kept open for lazy per-skin
     * reads; the raw file data never resides on the Java heap.
     * Call {@link #close()} when done.
     */
    public static SbReader fromFile(final Path path) throws IOException {
        // Read just enough to detect format (first 16 bytes)
        final byte[] hdr = new byte[16];
        try (FileChannel probe = FileChannel.open(path, StandardOpenOption.READ)) {
            probe.read(ByteBuffer.wrap(hdr));
        }
        final ByteBuffer hdrBuf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        final int magic = hdrBuf.getInt();
        if (magic != SbFormat.MAGIC) {
            throw new IOException(MALFORMED_MESSAGE + ": bad magic 0x"
                    + Integer.toHexString(magic));
        }
        final int flags = hdrBuf.getShort() & 0xFFFF;

        if ((flags & SbFormat.FLAG_SECTION2_LAZY) != 0) {
            // V2: use FileChannel — raw data never stays on heap
            final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            try {
                return constructV2FromChannel(channel);
            } catch (IOException e) {
                channel.close();
                throw e;
            }
        }

        // V1: eager monolithic — read entire file onto heap
        final byte[] data = Files.readAllBytes(path);
        return new SbReader(ByteBuffer.wrap(data));
    }

    /**
     * Parse an {@code .sbh} from an in-memory byte array (primarily useful
     * for tests and other callers that already hold the file contents).
     *
     * <p>For V2 format, lazy per-skin decompression is used but the
     * raw file bytes remain on the Java heap. Prefer {@link #fromFile(Path)}
     * for large catalogs.
     */
    public static SbReader fromBytes(final byte[] data) throws IOException {
        final ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        final int magic = buf.getInt();
        if (magic != SbFormat.MAGIC) {
            throw new IOException(MALFORMED_MESSAGE + ": bad magic 0x"
                    + Integer.toHexString(magic));
        }
        final int flags = buf.getShort() & 0xFFFF;

        if ((flags & SbFormat.FLAG_SECTION2_LAZY) != 0) {
            // V2: lazy per-skin from heap
            return constructV2FromBytes(data);
        }

        // V1: eager monolithic
        return new SbReader(ByteBuffer.wrap(data));
    }

    // ========================================================================
    //  V2 construction helpers
    // ========================================================================

    /** Build a V2 reader from a FileChannel (no heap allocation for raw file). */
    private static SbReader constructV2FromChannel(final FileChannel channel) throws IOException {
        // Read header (16 bytes)
        final ByteBuffer hdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, hdr, 0);
        final int magic = hdr.getInt();     // validated by caller
        final int flags = hdr.getShort() & 0xFFFF;
        final int gridN = hdr.getShort() & 0xFFFF;
        final int blockCount = hdr.getInt();
        final int skinCount = hdr.getInt();

        // Read Section 1 compressed data
        final ByteBuffer lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, lenBuf, 16);
        final int idxCompLen = lenBuf.getInt();

        final byte[] idxCompressed = new byte[idxCompLen];
        readFully(channel, ByteBuffer.wrap(idxCompressed), 20);
        final byte[] idxRaw = decompress(idxCompressed);
        final ByteBuffer idxBuf = ByteBuffer.wrap(idxRaw).order(ByteOrder.LITTLE_ENDIAN);

        final Section1Data s1 = parseBlockEntries(idxBuf, blockCount, skinCount, gridN);

        // Read Section 2 offset table
        final int section2Start = 20 + idxCompLen; // = 16 + 4 + idxCompLen
        final ByteBuffer s2Hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, s2Hdr, section2Start);

        final int payloadCount = s2Hdr.getInt();
        if (payloadCount != skinCount) {
            throw new IOException(MALFORMED_MESSAGE + ": payloadCount=" + payloadCount
                    + " != skinCount=" + skinCount);
        }
        final int offsetCount = s2Hdr.getInt();

        final int[] v2Offsets = new int[offsetCount];
        final int ofsTableStart = section2Start + 8;
        final ByteBuffer ofsBuf = ByteBuffer.allocate(offsetCount * 4).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, ofsBuf, ofsTableStart);
        for (int i = 0; i < offsetCount; i++) {
            v2Offsets[i] = ofsBuf.getInt();
        }

        return new SbReader(channel, gridN, s1, v2Offsets);
    }

    /** Build a V2 reader from a byte array (heap-backed). */
    private static SbReader constructV2FromBytes(final byte[] data) throws IOException {
        final ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Header (magic + flags already consumed by caller check)
        final int magic = buf.getInt();     // validated
        final int flags = buf.getShort() & 0xFFFF;
        final int gridN = buf.getShort() & 0xFFFF;
        final int blockCount = buf.getInt();
        final int skinCount = buf.getInt();

        // Section 1
        final int idxCompLen = buf.getInt();
        if (idxCompLen <= 0) {
            throw new IOException(MALFORMED_MESSAGE + ": empty index section");
        }
        final byte[] idxCompressed = new byte[idxCompLen];
        buf.get(idxCompressed);
        final byte[] idxRaw = decompress(idxCompressed);
        final ByteBuffer idxBuf = ByteBuffer.wrap(idxRaw).order(ByteOrder.LITTLE_ENDIAN);
        final Section1Data s1 = parseBlockEntries(idxBuf, blockCount, skinCount, gridN);

        // Section 2 offset table (from heap buffer)
        final int payloadCount = buf.getInt();
        if (payloadCount != skinCount) {
            throw new IOException(MALFORMED_MESSAGE + ": payloadCount=" + payloadCount
                    + " != skinCount=" + skinCount);
        }
        final int offsetCount = buf.getInt();
        final int[] v2Offsets = new int[offsetCount];
        for (int i = 0; i < offsetCount; i++) {
            v2Offsets[i] = buf.getInt();
        }

        return new SbReader(data, gridN, s1, v2Offsets);
    }

    // ========================================================================
    //  Accessors
    // ========================================================================

    /** The grid size that this archive was baked for. */
    public int gridN() { return gridN; }

    /** Producer string (e.g. {@code "sbh-converter"} or {@code "sculpt-bake-cli"}). */
    public String producer() { return producer; }

    /** Number of unique block entries in this archive. */
    public int blockCount() { return blocks.size(); }

    /** Number of unique skin payloads in this archive. */
    public int skinCount() { return skinHexHashes.length; }

    /**
     * All block keys known to this archive, in insertion (bake-key sorted)
     * order.
     */
    public Collection<BakeKey> listBlocks() {
        return bakeKeys;
    }

    /** Whether {@code key} is present in this archive. */
    public boolean hasBlock(final BakeKey key) {
        return blocks.containsKey(key);
    }

    /** Whether {@code key} (untinted) is present. */
    public boolean hasBlock(final BlockKey key) {
        return hasBlock(BakeKey.untinted(key));
    }

    /**
     * The chunk→skin-index array for {@code key}. Returns {@code null}
     * if the key is not present. The array length is always {@code gridN³}.
     */
    public int[] chunkSkinIndices(final BakeKey key) {
        final BlockInfo info = blocks.get(key);
        return info != null ? info.chunkSkinIndices() : null;
    }

    /**
     * Variant rotations for {@code key}, or an empty map if none exist.
     */
    public Map<String, VariantRotation> variantsFor(final BakeKey key) {
        final BlockInfo info = blocks.get(key);
        return info != null ? info.variants() : Collections.emptyMap();
    }

    /**
     * The hex-encoded skin hash at {@code index}. Indices are guaranteed
     * to be dense between 0 and {@link #skinCount()}.
     */
    public String skinHexHash(final int index) {
        return skinHexHashes[index];
    }

    /**
     * Look up a skin payload by its 64-char hex hash string.
     */
    public Optional<SkinPayload> findSkin(final String hexHash) {
        final Integer idx = skinHexIndex.get(hexHash);
        if (idx == null) return Optional.empty();
        try {
            return Optional.of(v2LazyMode ? parseSkinLazy(idx) : parseSkinEager(idx));
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    // ========================================================================
    //  Skin payload parsing
    // ========================================================================

    /** Parse skin from V1 eagerly-decompressed {@link #section2Data}. */
    private SkinPayload parseSkinEager(final int index) {
        final int start = section2Offsets[index];
        final int end = section2Offsets[index + 1];
        final ByteBuffer payBuf = ByteBuffer.wrap(section2Data, start, end - start)
                .order(ByteOrder.LITTLE_ENDIAN);
        return parseSkinFromBuffer(payBuf);
    }

    /** Read, decompress, and parse a single skin from V2 per-skin DEFLATE. */
    private SkinPayload parseSkinLazy(final int index) throws IOException {
        final byte[] compressed;
        if (fileChannel != null) {
            // FileChannel mode: positional reads (thread-safe)
            final long fileOfs = v2SkinOffsets[index];
            final ByteBuffer lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            readFully(fileChannel, lenBuf, fileOfs);
            final int compLen = lenBuf.getInt();
            compressed = new byte[compLen];
            readFully(fileChannel, ByteBuffer.wrap(compressed), fileOfs + 4);
        } else {
            // Heap-backed mode: slice from rawFileData byte[]
            final int fileOfs = v2SkinOffsets[index];
            final ByteBuffer lenBuf = ByteBuffer.wrap(rawFileData, fileOfs, 4)
                    .order(ByteOrder.LITTLE_ENDIAN);
            final int compLen = lenBuf.getInt();
            compressed = new byte[compLen];
            System.arraycopy(rawFileData, fileOfs + 4, compressed, 0, compLen);
        }

        final byte[] decompressed = decompress(compressed);
        final ByteBuffer payBuf = ByteBuffer.wrap(decompressed).order(ByteOrder.LITTLE_ENDIAN);
        return parseSkinFromBuffer(payBuf);
    }

    private static SkinPayload parseSkinFromBuffer(final ByteBuffer payBuf) {
        final int flags = payBuf.get() & 0xFF;
        payBuf.get(); // reserved

        final String uuid;
        if ((flags & SbFormat.SKIN_FLAG_HAS_UUID) != 0) {
            uuid = readUTF16(payBuf);
        } else {
            uuid = null;
        }

        final String value = readUTF16(payBuf);
        final String signature = readUTF16(payBuf);
        return new SkinPayload(value, signature, uuid);
    }

    // ========================================================================
    //  Resource management
    // ========================================================================

    /**
     * Release any native resources held by this reader (file channel).
     * Safe to call multiple times.
     */
    @Override
    public void close() throws IOException {
        if (fileChannel != null && fileChannel.isOpen()) {
            fileChannel.close();
        }
    }

    // ========================================================================
    //  Binary I/O helpers (little-endian)
    // ========================================================================

    private static String readUTF16(final ByteBuffer buf) {
        final int len = buf.getShort() & 0xFFFF;
        if (len == 0) return "";
        final byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readUTF32(final ByteBuffer buf) {
        final int len = buf.getInt();
        if (len == 0) return "";
        final byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Positional read from a FileChannel (thread-safe). */
    private static void readFully(
            final FileChannel channel,
            final ByteBuffer dst,
            final long position
    ) throws IOException {
        dst.clear();
        long pos = position;
        while (dst.hasRemaining()) {
            final int read = channel.read(dst, pos);
            if (read < 0) {
                throw new IOException("Unexpected EOF at file position " + pos);
            }
            pos += read;
        }
        dst.flip();
    }

    private static byte[] decompress(final byte[] compressed) throws IOException {
        try (InputStream is = new InflaterInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream baos = new ByteArrayOutputStream(compressed.length * 4)) {
            is.transferTo(baos);
            return baos.toByteArray();
        }
    }
}
