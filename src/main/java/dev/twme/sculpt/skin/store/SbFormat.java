package dev.twme.sculpt.skin.store;

/**
 * Format constants for the {@code .sbh} (Sculpt Baked Heads) binary archive.
 *
 * <p>SBH is a single-file binary catalog for pre-baked block-head skin
 * data in a compact, read-only catalog. The layout uses little-endian multi-byte
 * integers and zlib/DEFLATE compression for the index section.
 *
 * <p>See {@code sbh_converter.py} in the BlockBaking repo for the
 * authoritative Python encoder.
 */
public final class SbFormat {

    /** Magic bytes as a little-endian int ({@code "SBH1"} = 0x31484253). */
    public static final int MAGIC = 0x31484253;
    public static final String FILE_EXTENSION = ".sbh";

    // ── Header flags ──────────────────────────────────────────────────────

    /** Bit 0 of the flags field: Section 2 (skin payloads) is monolithic zlib-compressed (V1). */
    public static final int FLAG_SECTION2_COMPRESSED = 1;

    /** Bit 1 of the flags field: Section 2 uses per-skin DEFLATE with file-offset table (V2).
     *  Enables lazy loading — each skin is individually compressed so the reader
     *  can seek and decompress only the skins that are actually requested. */
    public static final int FLAG_SECTION2_LAZY = 2;

    // ── Skin payload flags ────────────────────────────────────────────────

    /** Bit 0 of the skin payload flags byte: a MineSkin UUID is present. */
    public static final int SKIN_FLAG_HAS_UUID = 1;

    private SbFormat() {}
}
