package dev.twme.sculpt.skin;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Logger;

import org.joml.Quaternionf;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.twme.sculpt.assets.model.ModelResolver;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.skin.store.HeadsStore;
import dev.twme.sculpt.skin.store.HeadsStore.StoredBlock;
import dev.twme.sculpt.skin.store.HeadsStore.StoredSkin;

/**
 * In-memory index over a {@link HeadsStore}: maps
 * {@code (BakeKey, ChunkCoord)} → {@link Entry} (skin payload).
 *
 * <p>The registry eagerly retains only the catalog's block keys. Per-block
 * chunk → skin-hash maps and variant tables load on first access through a
 * weighted Caffeine cache; the heavier {@code value}/{@code signature}/
 * {@code mineskinUuid} blobs live on disk and use a separate payload cache.
 * This keeps startup memory bounded even when all grid sizes are available.
 *
 * <p>The store is layered: runtime SQLite shadows the administrator-installed
 * read-only SBH catalog (see {@code LayeredHeadsStore}). All writes go to
 * SQLite; Sculpt never creates or modifies SBH files.
 *
 * <p>Caffeine is imported at the original coordinate
 * {@code com.github.benmanes.caffeine} — the
 * {@code DEVELOPMENT_PLAN.md} §8 shade rule relocates it to
 * {@code dev.twme.sculpt.shaded.caffeine} at package time, so runtime
 * resolution is via the shaded copy.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.skin.HeadsRegistry}).
 */
public final class HeadsRegistry {

    public record Entry(String skinHash, String textureValue, String textureSignature, String mineskinUuid) {}

    private record BlockIndex(
        Map<ChunkCoord, String> chunkHashes,
        Map<String, ModelResolver.VariantRotation> variants) {}

    private static final BlockIndex EMPTY_BLOCK_INDEX =
        new BlockIndex(Collections.emptyMap(), Collections.emptyMap());
    private static final long BLOCK_CACHE_CHUNK_BUDGET = 65_536L;

    private final Logger logger;
    private final int gridN;
    private final String version;
    private final HeadsStore store;
    private final Set<BakeKey> knownBakeKeys = ConcurrentHashMap.newKeySet();
    private final Set<BlockKey> knownBlocks = ConcurrentHashMap.newKeySet();
    private final Map<BlockKey, BakeKey> representativeKeys = new ConcurrentHashMap<>();
    private final Map<BlockKey, Map<String, ModelResolver.VariantRotation>> resolvedVariants =
        new ConcurrentHashMap<>();
    private final Map<BakeKey, BlockIndex> unpersistedBlockIndexes = new ConcurrentHashMap<>();
    private final Cache<BakeKey, BlockIndex> blockIndexCache;
    /**
     * One in-flight load per bake key. This prevents a burst of chunk edits from
     * queueing duplicate SQLite/SBH reads for the same cold block.
     */
    private final Map<BakeKey, CompletableFuture<Boolean>> prefetches = new ConcurrentHashMap<>();
    private final Map<BakeKey, CompletableFuture<Boolean>> indexPrefetches =
            new ConcurrentHashMap<>();
    private final Map<BakeKey, CompletableFuture<Boolean>> previewPrefetches =
            new ConcurrentHashMap<>();
    /** Content-addressed payload reads are shared across blocks and cells. */
    private final Map<String, CompletableFuture<Boolean>> skinPrefetches =
            new ConcurrentHashMap<>();
    private final Executor ioExecutor;
    // Caffeine cache of skin payloads keyed by hash. Loader hits the store
    // on miss. Dedup is automatic: a uniform stone block at gridN=4 has 64
    // chunks pointing at ~3 unique hashes, so 64 get() calls trigger at
    // most 3 loader invocations.
    private final Cache<String, Entry> skinCache;

    private HeadsRegistry(Logger logger, int gridN, String version, HeadsStore store,
                          int cacheCapacity) {
        this(logger, gridN, version, store, cacheCapacity, ForkJoinPool.commonPool());
    }

    private HeadsRegistry(Logger logger, int gridN, String version, HeadsStore store,
                          int cacheCapacity, Executor ioExecutor) {
        this.logger = logger;
        this.gridN = gridN;
        this.version = version;
        this.store = store;
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.blockIndexCache = Caffeine.newBuilder()
            .maximumWeight(BLOCK_CACHE_CHUNK_BUDGET)
            .weigher((BakeKey key, BlockIndex index) ->
                Math.max(1, index.chunkHashes().size()))
            .build();
        this.skinCache = Caffeine.newBuilder()
                .maximumSize(Math.max(64, cacheCapacity))
                .build();
    }

    /**
     * Construct a registry without a backing store. Used by tests that
     * register Entries directly and read them back without touching disk;
     * the in-memory Caffeine cache absorbs both sides of the round-trip.
     */
    public static HeadsRegistry empty(Logger logger, int gridN, String version) {
        return new HeadsRegistry(logger, gridN, version, new NullStore(gridN), 256);
    }

    /**
     * Construct a registry over {@code store}, enumerating its block keys.
     * Block indexes and skin payloads are loaded on demand.
     */
    public static HeadsRegistry loadFrom(Logger logger, HeadsStore store,
                                         int gridN, String version, int cacheCapacity) {
        return loadFrom(logger, store, gridN, version, cacheCapacity,
                ForkJoinPool.commonPool());
    }

    /**
     * Construct a registry using the supplied executor for cold block-index
     * and skin-payload reads. Catalog enumeration still happens in the caller,
     * so callers should invoke this factory from their startup loader thread.
     */
    public static HeadsRegistry loadFrom(Logger logger, HeadsStore store,
                                         int gridN, String version, int cacheCapacity,
                                         Executor ioExecutor) {
        if (store.gridN() != gridN) {
            throw new IllegalArgumentException("store gridN=" + store.gridN()
                    + " does not match registry gridN=" + gridN);
        }
        HeadsRegistry reg = new HeadsRegistry(logger, gridN, version, store,
                cacheCapacity, ioExecutor);
        reg.populateIndex();
        return reg;
    }

    private void populateIndex() {
        Collection<BakeKey> keys = store.listBlocks();
        for (BakeKey key : keys) {
            knownBakeKeys.add(key);
            knownBlocks.add(key.block());
            representativeKeys.compute(key.block(), (block, current) ->
                    current == null || (current.tintArgb() != 0 && key.tintArgb() == 0)
                            ? key : current);
        }
        String storeVersion = store.metadata().map(HeadsStore.Metadata::mcVersion).orElse("");
        logger.info("[heads-registry] indexed " + keys.size() + " block key(s) from store"
                + " (gridN=" + store.gridN()
                + (storeVersion.isEmpty() ? "" : ", mcVersion=" + storeVersion) + ")");
    }

    /**
     * Re-walk the backing store and merge any newly visible blocks into
     * the in-memory index. Idempotent: existing entries get overwritten
     * with the same data (chunk hashes are content-addressed and don't
     * change). Runtime-baked entries written to the writable layer survive
     * — they're still in {@code listBlocks()} after the reload.
     *
     * @return the net change in registered block count (positive when
     *         new blocks became visible; zero if nothing new appeared)
     */
    public synchronized int reindex() {
        int before = knownBakeKeys.size();
        blockIndexCache.invalidateAll();
        prefetches.clear();
        indexPrefetches.clear();
        previewPrefetches.clear();
        resolvedVariants.clear();
        populateIndex();
        return knownBakeKeys.size() - before;
    }

    public int gridN() { return gridN; }
    public String version() { return version; }

    long cachedBlockIndexCount() {
        blockIndexCache.cleanUp();
        return blockIndexCache.estimatedSize();
    }

    public boolean has(BakeKey key) {
        return !blockIndexFor(key).chunkHashes().isEmpty();
    }

    /**
     * Check the startup catalog without touching SQLite/SBH. This is safe on a
     * region thread and is intentionally separate from {@link #has(BakeKey)},
     * whose legacy contract may perform a cold read.
     */
    public boolean hasKnownBlock(BakeKey key) {
        return knownBakeKeys.contains(key);
    }

    public boolean hasKnownBlock(BlockKey key) {
        return hasKnownBlock(BakeKey.untinted(key));
    }

    /**
     * Whether the lightweight block index is already resident in memory.
     * Never loads from the backing store.
     */
    public boolean hasLoaded(BakeKey key) {
        return loadedBlockIndex(key) != null;
    }

    public boolean hasLoaded(BlockKey key) {
        return hasLoaded(BakeKey.untinted(key));
    }

    /** Whether a resident index contains this coordinate; never reads disk. */
    public boolean containsLoadedChunk(BakeKey key, ChunkCoord coord) {
        BlockIndex index = loadedBlockIndex(key);
        return index != null && index.chunkHashes().containsKey(coord);
    }

    /** Untinted convenience overload — equivalent to {@code has(BakeKey.untinted(key))}. */
    public boolean has(BlockKey key) {
        return has(BakeKey.untinted(key));
    }

    public Optional<Entry> get(BakeKey key, ChunkCoord coord) {
        Map<ChunkCoord, String> per = blockIndexFor(key).chunkHashes();
        String hash = per.get(coord);
        if (hash == null) return Optional.empty();
        return Optional.ofNullable(loadByHash(hash));
    }

    /**
     * Non-blocking counterpart to {@link #get(BakeKey, ChunkCoord)}. It only
     * consults resident indexes and payloads; a cold miss is represented by an
     * empty Optional and can be scheduled with {@link #prefetch(BakeKey)}.
     */
    public Optional<Entry> getIfLoaded(BakeKey key, ChunkCoord coord) {
        BlockIndex index = loadedBlockIndex(key);
        if (index == null) return Optional.empty();
        String hash = index.chunkHashes().get(coord);
        if (hash == null) return Optional.empty();
        Entry cached = skinCache.getIfPresent(hash);
        if (cached == null) {
            CompletableFuture<Boolean> completed = prefetches.get(key);
            if (completed != null && completed.isDone()
                    && !completed.isCompletedExceptionally()
                    && Boolean.TRUE.equals(completed.getNow(false))) {
                prefetches.remove(key, completed);
            }
        }
        return Optional.ofNullable(cached);
    }

    /** Non-blocking snapshot of all entries whose payloads are resident. */
    public Map<ChunkCoord, Entry> chunksForIfLoaded(BakeKey key) {
        BlockIndex index = loadedBlockIndex(key);
        if (index == null || index.chunkHashes().isEmpty()) return Collections.emptyMap();
        Map<ChunkCoord, Entry> out = new LinkedHashMap<>();
        for (Map.Entry<ChunkCoord, String> chunk : index.chunkHashes().entrySet()) {
            Entry entry = skinCache.getIfPresent(chunk.getValue());
            if (entry != null) out.put(chunk.getKey(), entry);
        }
        return out;
    }

    /**
     * Resolve every chunk's skin in one shot, populating the Caffeine
     * cache with any payloads that miss. The returned map is a fresh
     * snapshot — mutating it has no effect on the registry. Entries whose
     * payload lookup fails are omitted so callers see a consistent view
     * (no half-loaded chunks).
     */
    public Map<ChunkCoord, Entry> chunksFor(BakeKey key) {
        Map<ChunkCoord, String> per = blockIndexFor(key).chunkHashes();
        if (per.isEmpty()) return Collections.emptyMap();
        Map<ChunkCoord, Entry> out = new LinkedHashMap<>(Math.max(8, per.size() * 2));
        for (Map.Entry<ChunkCoord, String> e : per.entrySet()) {
            Entry entry = loadByHash(e.getValue());
            if (entry != null) out.put(e.getKey(), entry);
        }
        return out;
    }

    /** Untinted convenience overload. */
    public Map<ChunkCoord, Entry> chunksFor(BlockKey key) {
        return chunksFor(BakeKey.untinted(key));
    }

    /**
     * Return the skin entry for the <b>first</b> chunk of {@code key},
     * loading at most one skin payload. Used by the head-browser GUI to
     * get a preview icon without resolving every chunk.
     *
     * @return the first available entry, or {@code null} if the key is
     *         unknown or the skin fails to load
     */
    public Entry firstEntryFor(BakeKey key) {
        Map<ChunkCoord, String> per = blockIndexFor(key).chunkHashes();
        if (per.isEmpty()) return null;
        String firstHash = per.values().iterator().next();
        return loadByHash(firstHash);
    }

    /** Non-blocking counterpart to {@link #firstEntryFor(BakeKey)}. */
    public Entry firstEntryIfLoaded(BakeKey key) {
        BlockIndex index = loadedBlockIndex(key);
        if (index == null || index.chunkHashes().isEmpty()) return null;
        String firstHash = index.chunkHashes().values().iterator().next();
        return skinCache.getIfPresent(firstHash);
    }

    /**
     * Return the number of registered chunks for {@code key} from the
     * lightweight hash index — does <b>not</b> load any skin payloads.
     * Returns 0 if the key is unknown.
     */
    public int chunkCountFor(BakeKey key) {
        return blockIndexFor(key).chunkHashes().size();
    }

    /**
     * Non-blocking chunk count. Returns {@code -1} when the known key's index
     * is still cold, and {@code 0} when it is resident but empty/unknown.
     */
    public int chunkCountIfLoaded(BakeKey key) {
        BlockIndex index = loadedBlockIndex(key);
        return index == null ? -1 : index.chunkHashes().size();
    }

    /** Look up a previously-registered skin by its content hash, or null. */
    public Entry findByHash(String hash) {
        return loadByHash(hash);
    }

    /**
     * Pre-load every skin payload for {@code key} into the cache without
     * resolving full Entries. Used by the "preload on look" path so a
     * subsequent {@link #get} on the mining hot-path doesn't hit disk.
     * Returns the number of unique hashes warmed.
     */
    public int warm(BakeKey key) {
        return warm(blockIndexFor(key));
    }

    private int warm(BlockIndex index) {
        Map<ChunkCoord, String> per = index.chunkHashes();
        if (per.isEmpty()) return 0;
        int n = 0;
        // Dedup by hash so a uniform block doesn't trigger 64 redundant
        // loads — Caffeine.get is idempotent but we still want to count
        // unique payloads.
        Set<String> seen = new HashSet<>(per.values());
        for (String hash : seen) {
            if (skinCache.getIfPresent(hash) == null) {
                Entry e = loadFromStore(hash);
                if (e != null) {
                    skinCache.put(hash, e);
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Load a known block index and all referenced skin payloads off the caller's
     * thread. Calls for the same key share one future. The result is true only
     * when the index is non-empty and every referenced payload is available in
     * the cache after the load.
     */
    public CompletableFuture<Boolean> prefetch(BakeKey key) {
        if (!knownBakeKeys.contains(key)) return CompletableFuture.completedFuture(false);

        CompletableFuture<Boolean> inFlight = prefetches.get(key);
        if (inFlight != null) {
            if (!inFlight.isDone() || loadedBlockIndex(key) != null) return inFlight;
            prefetches.remove(key, inFlight);
        }

        CompletableFuture<Boolean> created = new CompletableFuture<>();
        inFlight = prefetches.putIfAbsent(key, created);
        if (inFlight != null) return inFlight;

        try {
            ioExecutor.execute(() -> {
                try {
                    BlockIndex index = blockIndexFor(key);
                    warm(index);
                    boolean success = !index.chunkHashes().isEmpty()
                            && allPayloadsLoaded(index);
                    created.complete(success);
                    // Keep successful futures as an O(1) readiness marker.
                    // If Caffeine later evicts a payload, getIfLoaded removes
                    // the marker and the next request reloads it.
                    if (!success) prefetches.remove(key, created);
                } catch (Throwable failure) {
                    created.completeExceptionally(failure);
                    prefetches.remove(key, created);
                }
            });
        } catch (RuntimeException rejected) {
            prefetches.remove(key, created);
            created.completeExceptionally(rejected);
        }
        return created;
    }

    /**
     * Prefetch one required cell. The block index and content-addressed skin
     * read are independently deduplicated, so sparse edits do not decode every
     * payload referenced by a grid-16 block.
     */
    public CompletableFuture<Boolean> prefetch(BakeKey key, ChunkCoord coord) {
        if (!knownBakeKeys.contains(key)) return CompletableFuture.completedFuture(false);
        if (getIfLoaded(key, coord).isPresent()) {
            return CompletableFuture.completedFuture(true);
        }
        return prefetchIndex(key).thenCompose(indexAvailable -> {
            if (!Boolean.TRUE.equals(indexAvailable)) {
                return CompletableFuture.completedFuture(false);
            }
            BlockIndex index = loadedBlockIndex(key);
            if (index == null) return CompletableFuture.completedFuture(false);
            String hash = index.chunkHashes().get(coord);
            return hash == null
                    ? CompletableFuture.completedFuture(false)
                    : prefetchSkin(hash);
        });
    }

    private CompletableFuture<Boolean> prefetchSkin(String hash) {
        if (skinCache.getIfPresent(hash) != null) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Boolean> inFlight = skinPrefetches.get(hash);
        if (inFlight != null) return inFlight;
        CompletableFuture<Boolean> created = new CompletableFuture<>();
        inFlight = skinPrefetches.putIfAbsent(hash, created);
        if (inFlight != null) return inFlight;
        try {
            ioExecutor.execute(() -> {
                try {
                    Entry loaded = loadFromStore(hash);
                    if (loaded != null) skinCache.put(hash, loaded);
                    created.complete(loaded != null);
                } catch (Throwable failure) {
                    created.completeExceptionally(failure);
                } finally {
                    skinPrefetches.remove(hash, created);
                }
            });
        } catch (RuntimeException rejected) {
            skinPrefetches.remove(hash, created);
            created.completeExceptionally(rejected);
        }
        return created;
    }

    /** Load only a known block index, without decoding any skin payloads. */
    public CompletableFuture<Boolean> prefetchIndex(BakeKey key) {
        if (!knownBakeKeys.contains(key)) return CompletableFuture.completedFuture(false);
        BlockIndex loaded = loadedBlockIndex(key);
        if (loaded != null) return CompletableFuture.completedFuture(!loaded.chunkHashes().isEmpty());
        CompletableFuture<Boolean> inFlight = indexPrefetches.get(key);
        if (inFlight != null) {
            if (!inFlight.isDone() || loadedBlockIndex(key) != null) return inFlight;
            indexPrefetches.remove(key, inFlight);
        }
        CompletableFuture<Boolean> created = new CompletableFuture<>();
        inFlight = indexPrefetches.putIfAbsent(key, created);
        if (inFlight != null) return inFlight;
        try {
            ioExecutor.execute(() -> {
                try {
                    BlockIndex index = blockIndexFor(key);
                    created.complete(!index.chunkHashes().isEmpty());
                } catch (Throwable failure) {
                    created.completeExceptionally(failure);
                } finally {
                    indexPrefetches.remove(key, created);
                }
            });
        } catch (RuntimeException rejected) {
            indexPrefetches.remove(key, created);
            created.completeExceptionally(rejected);
        }
        return created;
    }

    /** Load an index from any tint candidate for variant resolution. */
    public CompletableFuture<Boolean> prefetchIndex(BlockKey key) {
        java.util.List<BakeKey> candidates = new java.util.ArrayList<>();
        BakeKey representative = representativeKeys.get(key);
        if (representative != null) candidates.add(representative);
        for (BakeKey candidate : knownBakeKeys) {
            if (candidate.block().equals(key) && !candidate.equals(representative)) {
                candidates.add(candidate);
            }
        }
        return prefetchIndexCandidate(candidates, 0);
    }

    private CompletableFuture<Boolean> prefetchIndexCandidate(
            java.util.List<BakeKey> candidates, int index) {
        if (index >= candidates.size()) return CompletableFuture.completedFuture(false);
        BakeKey candidate = candidates.get(index);
        return prefetchIndex(candidate).thenCompose(loaded -> {
            BlockIndex blockIndex = loadedBlockIndex(candidate);
            if (blockIndex != null && !blockIndex.variants().isEmpty()) {
                representativeKeys.put(candidate.block(), candidate);
                resolvedVariants.put(candidate.block(), blockIndex.variants());
                return CompletableFuture.completedFuture(true);
            }
            return prefetchIndexCandidate(candidates, index + 1);
        });
    }

    /**
     * Load only the block index and its first skin payload. This is intended for
     * catalog summary icons; it avoids decoding every grid-16 payload merely to
     * render one GUI item.
     */
    public CompletableFuture<Boolean> prefetchPreview(BakeKey key) {
        if (!knownBakeKeys.contains(key)) return CompletableFuture.completedFuture(false);
        Entry preview = firstEntryIfLoaded(key);
        if (preview != null) return CompletableFuture.completedFuture(true);

        CompletableFuture<Boolean> inFlight = previewPrefetches.get(key);
        if (inFlight != null) {
            if (!inFlight.isDone() || firstEntryIfLoaded(key) != null) return inFlight;
            previewPrefetches.remove(key, inFlight);
        }
        CompletableFuture<Boolean> created = new CompletableFuture<>();
        inFlight = previewPrefetches.putIfAbsent(key, created);
        if (inFlight != null) return inFlight;

        try {
            ioExecutor.execute(() -> {
                try {
                    BlockIndex index = blockIndexFor(key);
                    if (index.chunkHashes().isEmpty()) {
                        created.complete(false);
                    } else {
                        String firstHash = index.chunkHashes().values().iterator().next();
                        created.complete(loadByHash(firstHash) != null);
                    }
                } catch (Throwable failure) {
                    created.completeExceptionally(failure);
                } finally {
                    previewPrefetches.remove(key, created);
                }
            });
        } catch (RuntimeException rejected) {
            previewPrefetches.remove(key, created);
            created.completeExceptionally(rejected);
        }
        return created;
    }

    /** Prefetch any representative tint for a block's variant table. */
    public CompletableFuture<Boolean> prefetch(BlockKey key) {
        java.util.List<BakeKey> candidates = new java.util.ArrayList<>();
        BakeKey representative = representativeKeys.get(key);
        if (representative != null) candidates.add(representative);
        for (BakeKey candidate : knownBakeKeys) {
            if (candidate.block().equals(key) && !candidate.equals(representative)) {
                candidates.add(candidate);
            }
        }
        return prefetchVariantCandidate(candidates, 0);
    }

    private CompletableFuture<Boolean> prefetchVariantCandidate(
            java.util.List<BakeKey> candidates, int index) {
        if (index >= candidates.size()) return CompletableFuture.completedFuture(false);
        BakeKey candidate = candidates.get(index);
        return prefetch(candidate).thenCompose(loaded -> {
            BlockIndex blockIndex = loadedBlockIndex(candidate);
            if (blockIndex != null && !blockIndex.variants().isEmpty()) {
                representativeKeys.put(candidate.block(), candidate);
                resolvedVariants.put(candidate.block(), blockIndex.variants());
                return CompletableFuture.completedFuture(true);
            }
            return prefetchVariantCandidate(candidates, index + 1);
        });
    }

    private boolean allPayloadsLoaded(BlockIndex index) {
        if (index.chunkHashes().isEmpty()) return false;
        for (String hash : new HashSet<>(index.chunkHashes().values())) {
            if (skinCache.getIfPresent(hash) == null) return false;
        }
        return true;
    }

    private BlockIndex blockIndexFor(BakeKey key) {
        BlockIndex unpersisted = unpersistedBlockIndexes.get(key);
        if (unpersisted != null) return unpersisted;
        if (!knownBakeKeys.contains(key)) return EMPTY_BLOCK_INDEX;
        return blockIndexCache.get(key, this::loadBlockIndex);
    }

    private BlockIndex loadedBlockIndex(BakeKey key) {
        BlockIndex unpersisted = unpersistedBlockIndexes.get(key);
        if (unpersisted != null) return unpersisted;
        return blockIndexCache.getIfPresent(key);
    }

    private BlockIndex loadBlockIndex(BakeKey key) {
        return store.readBlock(key)
                .filter(block -> !block.chunkHashes().isEmpty())
                .map(block -> new BlockIndex(
                        Map.copyOf(block.chunkHashes()),
                        block.variants().isEmpty()
                                ? Collections.emptyMap()
                                : Map.copyOf(block.variants())))
                .orElse(EMPTY_BLOCK_INDEX);
    }

    private Entry loadByHash(String hash) {
        Entry cached = skinCache.getIfPresent(hash);
        if (cached != null) return cached;
        Entry loaded = loadFromStore(hash);
        if (loaded != null) skinCache.put(hash, loaded);
        return loaded;
    }

    private Entry loadFromStore(String hash) {
        return store.readSkin(hash).map(StoredSkin::toEntry).orElse(null);
    }

    /**
     * Register chunks for {@code key} discovered at runtime by
     * {@code skin.bake.BlockBaker}. Replaces any existing entry for the
     * same key. The payload Entries are written through to the store (if
     * writable) so a restart finds them again, then prewarmed into the
     * cache so the immediately-following spawn doesn't pay a disk
     * round-trip.
     */
    public void register(BakeKey key, Map<ChunkCoord, Entry> chunks) {
        register(key, chunks, Collections.emptyMap());
    }

    public synchronized void register(BakeKey key, Map<ChunkCoord, Entry> chunks,
                                      Map<String, ModelResolver.VariantRotation> variants) {
        registerInternal(key, chunks, variants, false);
    }

    /**
     * Merge a runtime-baked subset into the existing block index. The merged
     * index is persisted atomically so separate sibling batches cannot
     * overwrite each other or hide entries supplied by an installed catalog.
     */
    public synchronized void registerPartial(
            BakeKey key, Map<ChunkCoord, Entry> chunks,
            Map<String, ModelResolver.VariantRotation> variants) {
        registerInternal(key, chunks, variants, true);
    }

    private void registerInternal(
            BakeKey key, Map<ChunkCoord, Entry> chunks,
            Map<String, ModelResolver.VariantRotation> variants,
            boolean mergeExisting) {
        BlockIndex existing = mergeExisting ? blockIndexFor(key) : EMPTY_BLOCK_INDEX;
        int expectedSize = existing.chunkHashes().size() + chunks.size();
        Map<ChunkCoord, String> chunkHashes = new LinkedHashMap<>(Math.max(8, expectedSize * 2));
        if (mergeExisting) chunkHashes.putAll(existing.chunkHashes());
        for (Map.Entry<ChunkCoord, Entry> e : chunks.entrySet()) {
            chunkHashes.put(e.getKey(), e.getValue().skinHash());
            skinCache.put(e.getValue().skinHash(), e.getValue());
        }
        Map<String, ModelResolver.VariantRotation> effectiveVariants =
            variants != null && !variants.isEmpty()
                ? Map.copyOf(variants)
                : mergeExisting ? existing.variants() : Collections.emptyMap();
        BlockIndex blockIndex = new BlockIndex(
            Map.copyOf(chunkHashes),
            effectiveVariants);
        knownBakeKeys.add(key);
        knownBlocks.add(key.block());
        if (blockIndex.variants().isEmpty()) {
            representativeKeys.putIfAbsent(key.block(), key);
        } else {
            representativeKeys.put(key.block(), key);
            resolvedVariants.put(key.block(), blockIndex.variants());
        }

        boolean persisted = false;
        if (store.isWritable()) {
            try {
                Map<String, StoredSkin> skins = new LinkedHashMap<>();
                for (Entry entry : chunks.values()) {
                    StoredSkin skin = StoredSkin.from(entry);
                    skins.putIfAbsent(skin.hash(), skin);
                }
                store.writeBatch(
                        new StoredBlock(key, chunkHashes, blockIndex.variants()),
                        skins.values());
                persisted = true;
            } catch (RuntimeException re) {
                logger.warning("[heads-registry] persistence write failed for " + key + ": " + re.getMessage());
            }
        }
        if (persisted) unpersistedBlockIndexes.remove(key);
        else unpersistedBlockIndexes.put(key, blockIndex);
        blockIndexCache.put(key, blockIndex);
        prefetches.remove(key);
        indexPrefetches.remove(key);
        previewPrefetches.remove(key);
    }

    /**
     * Look up the world-space rotation for a specific blockstate variant
     * of {@code key}. See the prior implementation's contract: identity
     * is returned for unknown keys / variants so blocks predating the
     * variant map render in canonical orientation.
     */
    public Quaternionf rotationFor(BlockKey key, String variantKey) {
        Map<String, ModelResolver.VariantRotation> variants = variantsFor(key);
        if (variantKey == null) return new Quaternionf();
        ModelResolver.VariantRotation rot = variants.get(variantKey);
        return rot != null ? rot.toQuat() : new Quaternionf();
    }

    /**
     * Non-blocking variant lookup. An empty map means the representative index
     * is not resident yet (or the block has no variants); callers on hot paths
     * must not replace this with {@link #variantsFor(BlockKey)}.
     */
    public Map<String, ModelResolver.VariantRotation> variantsForIfLoaded(BlockKey key) {
        Map<String, ModelResolver.VariantRotation> cached = resolvedVariants.get(key);
        if (cached != null) return cached;
        BakeKey representative = representativeKeys.get(key);
        if (representative != null) {
            BlockIndex index = loadedBlockIndex(representative);
            // The representative is normally the untinted key and carries the
            // variant table. Avoid scanning every tint on a hot path while that
            // representative index is still cold; the async prefetch will
            // populate it before the next render.
            if (index == null) return Collections.emptyMap();
            if (!index.variants().isEmpty()) {
                resolvedVariants.putIfAbsent(key, index.variants());
                return index.variants();
            }
        }
        for (BakeKey candidate : knownBakeKeys) {
            if (!candidate.block().equals(key)) continue;
            BlockIndex index = loadedBlockIndex(candidate);
            if (index != null && !index.variants().isEmpty()) {
                resolvedVariants.putIfAbsent(key, index.variants());
                return index.variants();
            }
        }
        return Collections.emptyMap();
    }

    /**
     * Returns {@code null} while every candidate index is cold, otherwise
     * returns whether a resident candidate supplied a variant table (possibly
     * an explicitly empty table for a non-orientable block).
     */
    public Boolean variantsLoadedIfPresent(BlockKey key) {
        if (resolvedVariants.containsKey(key)) return true;
        boolean sawCandidate = false;
        for (BakeKey candidate : knownBakeKeys) {
            if (!candidate.block().equals(key)) continue;
            sawCandidate = true;
            BlockIndex index = loadedBlockIndex(candidate);
            if (index == null) continue;
            if (!index.variants().isEmpty()) return true;
            // Keep looking: an untinted index may be empty while a tinted
            // runtime registration carries the variant table.
        }
        return sawCandidate && allCandidatesLoaded(key) ? Boolean.FALSE : null;
    }

    private boolean allCandidatesLoaded(BlockKey key) {
        for (BakeKey candidate : knownBakeKeys) {
            if (candidate.block().equals(key) && loadedBlockIndex(candidate) == null) return false;
        }
        return true;
    }

    public Quaternionf rotationForIfLoaded(BlockKey key, String variantKey) {
        if (variantKey == null) return new Quaternionf();
        ModelResolver.VariantRotation rotation = variantsForIfLoaded(key).get(variantKey);
        return rotation == null ? new Quaternionf() : rotation.toQuat();
    }

    /** Live view of every installed and runtime block across all tints. */
    public Set<BlockKey> knownBlockKeys() {
        return Collections.unmodifiableSet(knownBlocks);
    }

    public Map<String, ModelResolver.VariantRotation> variantsFor(BlockKey key) {
        return resolvedVariants.computeIfAbsent(key, this::loadVariantsForBlock);
    }

    private Map<String, ModelResolver.VariantRotation> loadVariantsForBlock(BlockKey key) {
        BakeKey representative = representativeKeys.get(key);
        if (representative != null) {
            Map<String, ModelResolver.VariantRotation> variants =
                blockIndexFor(representative).variants();
            if (!variants.isEmpty()) return variants;
        }
        for (BakeKey candidate : knownBakeKeys) {
            if (!candidate.block().equals(key) || candidate.equals(representative)) continue;
            Map<String, ModelResolver.VariantRotation> variants = blockIndexFor(candidate).variants();
            if (!variants.isEmpty()) {
                representativeKeys.put(key, candidate);
                return variants;
            }
        }
        return Collections.emptyMap();
    }

    /**
     * Forget every runtime registration for {@code block} (untinted plus
     * every tinted variant) so the next request re-runs the bake. Installed
     * SBH entries are cleared from the in-memory index too, but their read-only
     * files remain untouched and are restored by reindexing or restarting.
     */
    public boolean invalidate(BlockKey block) {
        boolean removed = knownBakeKeys.removeIf(k -> k.block().equals(block));
        removed |= blockIndexCache.asMap().keySet().removeIf(k -> k.block().equals(block));
        removed |= unpersistedBlockIndexes.keySet().removeIf(k -> k.block().equals(block));
        representativeKeys.remove(block);
        resolvedVariants.remove(block);
        prefetches.keySet().removeIf(k -> k.block().equals(block));
        indexPrefetches.keySet().removeIf(k -> k.block().equals(block));
        previewPrefetches.keySet().removeIf(k -> k.block().equals(block));
        // knownBlocks is the deduped-by-BlockKey view; removing here keeps
        // callers from offering a block that no longer has any registered
        // variants. Installed entries still round-trip through reindex() at
        // server restart, so they can be rebaked on demand.
        knownBlocks.remove(block);
        if (store.isWritable()) {
            try { store.removeBlock(block); }
            catch (RuntimeException re) { logger.warning("[heads-registry] store remove failed: " + re.getMessage()); }
        }
        return removed;
    }

    /**
     * Forget every runtime and installed entry. Changing tile
     * rotation needs fresh PNGs uploaded because the dedup hash is computed
     * pre-rotation, so existing cached entries would otherwise mask the change.
     */
    public int invalidateAll() {
        int n = knownBakeKeys.size();
        knownBakeKeys.clear();
        representativeKeys.clear();
        resolvedVariants.clear();
        unpersistedBlockIndexes.clear();
        blockIndexCache.invalidateAll();
        prefetches.clear();
        indexPrefetches.clear();
        previewPrefetches.clear();
        // skinCache stays — its only consumer is findByHash, which is
        // bypassed when TileRotations.consumeStale is true.
        if (store.isWritable()) {
            try { store.clearBlocks(); }
            catch (RuntimeException re) { logger.warning("[heads-registry] store clear failed: " + re.getMessage()); }
        }
        return n;
    }

    /**
     * Build a runtime {@link HeadSkin} from a registry entry. The skin has
     * no tile bitmaps (those were baked away into the MineSkin texture)
     * and no chunk bookkeeping — it exists purely to feed
     * {@code assemble.HeadItemFactory#build} on the spawn path.
     */
    public static HeadSkin toHeadSkin(Entry e) {
        // Derive the id from skinHash so multiple chunks sharing a skin
        // map to the same HeadSkin.id() and HeadItemFactory's ItemStack
        // cache actually hits across chunks and across breaks (a random
        // id here would defeat that cache entirely).
        HeadSkin h = new HeadSkin(HeadSkin.idFromHash(e.skinHash()), e.skinHash(), Collections.emptyMap());
        h.texture(e.textureValue(), e.textureSignature(), e.mineskinUuid());
        h.state(SkinState.COMPLETED);
        return h;
    }

    /** Internal no-op store for {@link #empty} / tests that don't need persistence. */
    private static final class NullStore implements HeadsStore {
        private final int gridN;
        private NullStore(int gridN) { this.gridN = gridN; }
        @Override public int gridN() { return gridN; }
        @Override public Optional<Metadata> metadata() { return Optional.empty(); }
        @Override public Collection<BakeKey> listBlocks() { return Collections.emptyList(); }
        @Override public Optional<StoredBlock> readBlock(BakeKey key) { return Optional.empty(); }
        @Override public Optional<StoredSkin> readSkin(String hash) { return Optional.empty(); }
        @Override public boolean isWritable() { return false; }
        @Override public void close() {}
    }
}
