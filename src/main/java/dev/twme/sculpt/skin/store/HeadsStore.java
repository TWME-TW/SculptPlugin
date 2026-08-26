package dev.twme.sculpt.skin.store;

import java.io.Closeable;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import dev.twme.sculpt.assets.model.ModelResolver.VariantRotation;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.skin.HeadsRegistry;

/**
 * Grid-specific storage contract shared by the writable SQLite runtime cache
 * and read-only SBH catalogs. No on-disk format types leak through this API.
 */
public interface HeadsStore extends Closeable {

    record Metadata(String mcVersion, String producer) {}

    record StoredBlock(
            BakeKey key,
            Map<ChunkCoord, String> chunkHashes,
            Map<String, VariantRotation> variants) {}

    record StoredSkin(String hash, String value, String signature, String mineskinUuid) {
        public HeadsRegistry.Entry toEntry() {
            return new HeadsRegistry.Entry(hash, value, signature, mineskinUuid);
        }

        public static StoredSkin from(HeadsRegistry.Entry entry) {
            return new StoredSkin(
                    entry.skinHash(), entry.textureValue(),
                    entry.textureSignature(), entry.mineskinUuid());
        }
    }

    /** The one lattice resolution exposed by this store view. */
    int gridN();

    Optional<Metadata> metadata();

    /** Enumerate every block known to this grid view. */
    Collection<BakeKey> listBlocks();

    Optional<StoredBlock> readBlock(BakeKey key);

    Optional<StoredSkin> readSkin(String hash);

    default boolean skinExists(String hash) {
        return readSkin(hash).isPresent();
    }

    default boolean isWritable() { return false; }

    default void writeBlock(StoredBlock block) {
        throw new UnsupportedOperationException("read-only store");
    }

    default void writeSkin(StoredSkin skin) {
        throw new UnsupportedOperationException("read-only store");
    }

    /**
     * Persist a complete block index and its newly available skin payloads as
     * one atomic unit. SQLite overrides this with one transaction.
     */
    default void writeBatch(StoredBlock block, Collection<StoredSkin> skins) {
        for (StoredSkin skin : skins) writeSkin(skin);
        writeBlock(block);
    }

    /** Remove every tinted/untinted runtime key for this block and grid. */
    default void removeBlock(BlockKey block) {
        throw new UnsupportedOperationException("read-only store");
    }

    /** Remove block indexes for this grid; unreferenced skins may be retained. */
    default void clearBlocks() {
        throw new UnsupportedOperationException("read-only store");
    }

    @Override default void close() {}
}
