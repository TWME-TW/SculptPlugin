package dev.twme.sculpt.skin.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;

/**
 * Two-layer {@link HeadsStore}: writable runtime SQLite data shadows one
 * administrator-installed, read-only SBH catalog.
 *
 * <p>Writes always go to SQLite. Sculpt never creates or modifies the SBH
 * catalog, which is installed at {@code heads/heads-<grid>.sbh}.</p>
 */
public final class LayeredHeadsStore implements HeadsStore {

    private final HeadsStore writable;
    private final HeadsStore installedCatalog;

    /**
     * @param writable runtime SQLite store
     * @param installedCatalog the optional administrator-installed SBH catalog
     */
    public LayeredHeadsStore(HeadsStore writable, HeadsStore installedCatalog) {
        if (writable == null) throw new IllegalArgumentException("writable store is required");
        if (installedCatalog != null && installedCatalog.gridN() != writable.gridN()) {
            throw new IllegalArgumentException("store gridN=" + installedCatalog.gridN()
                    + " does not match writable gridN=" + writable.gridN());
        }
        this.writable = writable;
        this.installedCatalog = installedCatalog;
    }

    public HeadsStore writableLayer() { return writable; }
    public Optional<HeadsStore> installedCatalog() { return Optional.ofNullable(installedCatalog); }

    @Override
    public int gridN() { return writable.gridN(); }

    @Override
    public Optional<Metadata> metadata() {
        Optional<Metadata> runtime = writable.metadata();
        return runtime.isPresent() || installedCatalog == null
                ? runtime : installedCatalog.metadata();
    }

    @Override
    public Collection<BakeKey> listBlocks() {
        Set<BakeKey> out = new LinkedHashSet<>(writable.listBlocks());
        if (installedCatalog != null) out.addAll(installedCatalog.listBlocks());
        return out;
    }

    @Override
    public Optional<StoredBlock> readBlock(BakeKey key) {
        Optional<StoredBlock> runtime = writable.readBlock(key);
        return runtime.isPresent() || installedCatalog == null
                ? runtime : installedCatalog.readBlock(key);
    }

    @Override
    public Optional<StoredSkin> readSkin(String hash) {
        Optional<StoredSkin> runtime = writable.readSkin(hash);
        return runtime.isPresent() || installedCatalog == null
                ? runtime : installedCatalog.readSkin(hash);
    }

    @Override public boolean isWritable() { return writable.isWritable(); }
    @Override public void writeBlock(StoredBlock block) { writable.writeBlock(block); }
    @Override public void writeSkin(StoredSkin skin) { writable.writeSkin(skin); }
    @Override public void writeBatch(StoredBlock block, Collection<StoredSkin> skins) {
        writable.writeBatch(block, skins);
    }
    @Override public void removeBlock(BlockKey block) { writable.removeBlock(block); }
    @Override public void clearBlocks() { writable.clearBlocks(); }

    @Override
    public void close() {
        ArrayList<RuntimeException> errors = new ArrayList<>();
        try {
            writable.close();
        } catch (RuntimeException e) {
            errors.add(e);
        }
        if (installedCatalog != null) {
            try {
                installedCatalog.close();
            } catch (RuntimeException e) {
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) throw errors.getFirst();
    }
}
