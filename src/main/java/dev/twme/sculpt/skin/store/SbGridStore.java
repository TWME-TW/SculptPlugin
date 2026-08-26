package dev.twme.sculpt.skin.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import dev.twme.sculpt.assets.model.ModelResolver.VariantRotation;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.ChunkCoord;

/**
 * Wraps an {@link SbReader} as a read-only {@link HeadsStore}, bridging
 * the SBH binary format into the existing layered-store pipeline.
 *
 * <p>The reader provides the block index (chunk→skin hash map) and the
 * skin payloads; this adapter converts between the SBH internal arrays
 * and the format-neutral records that the registry consumes.
 *
 * <p>Read-only — writes throw {@link UnsupportedOperationException}.
 */
public final class SbGridStore implements HeadsStore {

    private final SbReader reader;

    public SbGridStore(final SbReader reader) {
        this.reader = reader;
    }

    /** Expose the underlying reader (for direct access if needed). */
    public SbReader reader() { return reader; }

    @Override
    public int gridN() { return reader.gridN(); }

    @Override
    public Optional<Metadata> metadata() {
        return Optional.of(new Metadata("", reader.producer()));
    }

    @Override
    public Collection<BakeKey> listBlocks() {
        return reader.listBlocks();
    }

    @Override
    public Optional<StoredBlock> readBlock(final BakeKey key) {
        final int[] skinIndices = reader.chunkSkinIndices(key);
        if (skinIndices == null) return Optional.empty();

        final int n = reader.gridN();
        final Map<ChunkCoord, String> chunkHashes = new LinkedHashMap<>(
                Math.max(8, skinIndices.length * 2));
        int arrIdx = 0;
        for (int y = 0; y < n; y++) {
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    final int skinIdx = skinIndices[arrIdx++];
                    final String hexHash = reader.skinHexHash(skinIdx);
                    chunkHashes.put(new ChunkCoord(x, y, z), hexHash);
                }
            }
        }

        final Map<String, VariantRotation> variants = reader.variantsFor(key);
        return Optional.of(new StoredBlock(key, chunkHashes, variants));
    }

    @Override
    public Optional<StoredSkin> readSkin(final String hash) {
        return reader.findSkin(hash).map(p -> new StoredSkin(
                hash, p.value(), p.signature(), p.mineskinUuid()));
    }

    @Override
    public boolean isWritable() { return false; }

    @Override
    public void close() {
        try {
            reader.close(); // releases FileChannel in V2 lazy mode; no-op in V1
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to close SBH reader", e);
        }
    }
}
