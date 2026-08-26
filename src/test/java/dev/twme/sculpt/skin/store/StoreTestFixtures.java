package dev.twme.sculpt.skin.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.twme.sculpt.assets.model.ModelResolver.VariantRotation;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;

final class StoreTestFixtures {

    private StoreTestFixtures() {}

    static String hash(int value) {
        return String.format("%064x", value);
    }

    static HeadsStore.StoredSkin skin(int value) {
        String hash = hash(value);
        return new HeadsStore.StoredSkin(hash, "value-" + value,
                "signature-" + value, "uuid-" + value);
    }

    static HeadsStore.StoredBlock fullBlock(
            int gridN, BakeKey key, String hash,
            Map<String, VariantRotation> variants) {
        Map<ChunkCoord, String> chunks = new LinkedHashMap<>();
        for (int y = 0; y < gridN; y++) {
            for (int z = 0; z < gridN; z++) {
                for (int x = 0; x < gridN; x++) {
                    chunks.put(new ChunkCoord(x, y, z), hash);
                }
            }
        }
        return new HeadsStore.StoredBlock(key, Map.copyOf(chunks), Map.copyOf(variants));
    }

    static void writeSbh(Path path, int gridN,
                         Collection<HeadsStore.StoredBlock> blocks,
                         Collection<HeadsStore.StoredSkin> skins) throws IOException {
        MemoryStore store = new MemoryStore(gridN, true);
        for (HeadsStore.StoredSkin skin : skins) store.writeSkin(skin);
        for (HeadsStore.StoredBlock block : blocks) store.writeBlock(block);
        SbhFixtureWriter.write(path, store, "test-catalog");
    }

    static final class MemoryStore implements HeadsStore {
        private final int gridN;
        private final boolean writable;
        private final Map<BakeKey, StoredBlock> blocks = new LinkedHashMap<>();
        private final Map<String, StoredSkin> skins = new LinkedHashMap<>();

        MemoryStore(int gridN, boolean writable) {
            this.gridN = gridN;
            this.writable = writable;
        }

        @Override public int gridN() { return gridN; }

        @Override public Optional<Metadata> metadata() {
            return Optional.of(new Metadata("1.21.11", "test"));
        }

        @Override public Collection<BakeKey> listBlocks() {
            return List.copyOf(blocks.keySet());
        }

        @Override public Optional<StoredBlock> readBlock(BakeKey key) {
            return Optional.ofNullable(blocks.get(key));
        }

        @Override public Optional<StoredSkin> readSkin(String hash) {
            return Optional.ofNullable(skins.get(hash));
        }

        @Override public boolean isWritable() { return writable; }

        @Override public void writeBlock(StoredBlock block) {
            requireWritable();
            blocks.put(block.key(), block);
        }

        @Override public void writeSkin(StoredSkin skin) {
            requireWritable();
            skins.put(skin.hash(), skin);
        }

        @Override public void writeBatch(StoredBlock block, Collection<StoredSkin> skins) {
            requireWritable();
            for (StoredSkin skin : skins) this.skins.put(skin.hash(), skin);
            if (block != null) blocks.put(block.key(), block);
        }

        @Override public void removeBlock(BlockKey block) {
            requireWritable();
            blocks.keySet().removeIf(key -> key.block().equals(block));
        }

        @Override public void clearBlocks() {
            requireWritable();
            blocks.clear();
        }

        private void requireWritable() {
            if (!writable) throw new UnsupportedOperationException("read-only test store");
        }
    }
}
