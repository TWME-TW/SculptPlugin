package dev.twme.sculpt;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Quaternionf;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.assets.shape.BlockVisualShapeCache;
import dev.twme.sculpt.assets.shape.BlockVisualShapeResolver;
import dev.twme.sculpt.blueprint.BlueprintManager;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.core.ChunkHead;
import dev.twme.sculpt.core.HeadResolver;
import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.PlayerHeadTexture;
import dev.twme.sculpt.core.PlayerHeadTextureCodec;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.editor.BlockPlaceBuildChecker;
import dev.twme.sculpt.editor.SculptControlsListener;
import dev.twme.sculpt.editor.SculptEditListener;
import dev.twme.sculpt.editor.WandListener;
import dev.twme.sculpt.gui.HeadBrowserListener;
import dev.twme.sculpt.integration.SculptPasteHandler;
import dev.twme.sculpt.lang.LanguageManager;
import dev.twme.sculpt.nms.BlockTintReader;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.plugin.NonBakeableBlocks;
import dev.twme.sculpt.plugin.SculptBlueprintCommand;
import dev.twme.sculpt.plugin.SculptCommand;
import dev.twme.sculpt.plugin.SculptConfig;
import dev.twme.sculpt.plugin.SculptConfigMigrator;
import dev.twme.sculpt.plugin.SculptHeadsCommand;
import dev.twme.sculpt.plugin.SculptFillCommand;
import dev.twme.sculpt.plugin.SculptDisplayCommand;
import dev.twme.sculpt.plugin.SculptModeCommand;
import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.plugin.SculptWandCommand;
import dev.twme.sculpt.plugin.FillConverter;
import dev.twme.sculpt.plugin.RuntimeHealth;
import dev.twme.sculpt.render.TextBlockRenderer;
import dev.twme.sculpt.render.text.TextDisplayBlockRenderer;
import dev.twme.sculpt.render.text.TextDisplayMaterialSupport;
import dev.twme.sculpt.render.text.TextDisplayTextureCache;
import dev.twme.sculpt.skin.HeadsRegistry;
import dev.twme.sculpt.skin.SkinDiskCache;
import dev.twme.sculpt.skin.SkinUploader;
import dev.twme.sculpt.skin.bake.BlockBaker;
import dev.twme.sculpt.skin.store.HeadsStore;
import dev.twme.sculpt.skin.store.LayeredHeadsStore;
import dev.twme.sculpt.skin.store.SbFormat;
import dev.twme.sculpt.skin.store.SbGridStore;
import dev.twme.sculpt.skin.store.SbReader;
import dev.twme.sculpt.skin.store.SqliteHeadsStore;
import dev.twme.sculpt.transport.bukkit.BukkitTransportSession;
import dev.twme.sculpt.util.MessageUtil;
import dev.twme.sculpt.util.PlatformDetector;
import dev.twme.sculpt.util.YamlMigrationSupport;

/**
 * Main plugin class for Sculpt (v2 ReDesign).
 *
 * <p>Lifecycle is driven entirely by PDC-persisted entities — there are
 * no external .sculpt files. The plugin scans ItemDisplay entities on
 * ChunkLoad, reconstructs SculptBlock + OctreeNode trees from their
 * PDC data, and manages editing via {@link SculptEditListener}.
 *
 * <p>Per ReDesign.md §1.7 (實體即資料庫): entities = database.
 * Every SculptBlock state is stored in the root entity's PersistentDataContainer.
 */
public final class Sculpt extends JavaPlugin {

    /** Resolution 1 is a whole-block editing tool and has no rendered cells. */
    private static final int[] TEXTURED_GRIDS = {2, 4, 8, 16};

    /*
     * Disable JOML's Unsafe usage early. JOML 1.10.8 (bundled by Paper at
     * runtime) uses sun.misc.Unsafe::objectFieldOffset, which emits a terminal
     * deprecation warning on Java 25+.  The fix (jdk.internal.misc.Unsafe
     * migration) is targeted for JOML 1.10.9, not yet released.
     *
     * Setting this system property before any JOML class is loaded tells JOML
     * to skip Unsafe entirely.  If Paper has already loaded JOML before this
     * plugin's class is loaded, the property is ignored and the server admin
     * should add  -Djoml.nounsafe=true  to the JVM flags instead.
     */
    static {
        System.setProperty("joml.nounsafe", "true");
    }

    // ---- configuration snapshot ------------------------------------------
    private volatile SculptConfig config;
    /** Startup-only settings are compared on reload so restart warnings remain accurate. */
    private SculptConfig startupConfig;

    // ---- skin / registry pipeline ----------------------------------------
    /** All active grid-size stores, for graceful shutdown. */
    private final List<HeadsStore> allStores = new ArrayList<>();
    /** Registries keyed by rendered grid size (2, 4, 8, 16). */
    private final Map<Integer, HeadsRegistry> headsRegistries = new ConcurrentHashMap<>();

    // ---- bake pipeline (optional, needs MineSkin API key) ----------------
    private SkinUploader uploader;
    private SkinDiskCache diskCache;
    private final Map<Integer, BlockBaker> bakers = new ConcurrentHashMap<>();
    private ExecutorService bakerExecutor;
    private ExecutorService registryExecutor;
    private ExecutorService textRenderExecutor;
    private ExecutorService visualShapeExecutor;
    private TextDisplayTextureCache textDisplayTextureCache;
    private TextDisplayBlockRenderer textDisplayBlockRenderer;
    private BlockVisualShapeCache visualShapeCache;
    private volatile boolean disabling;

    // ---- data folder (for reload) ---------------------------------------
    private Path dataDir;

    // ---- non-bakeable block list -----------------------------------------
    private volatile NonBakeableBlocks nonBakeableBlocks;

    // ---- editor ----------------------------------------------------------
    private BlockPlaceBuildChecker buildChecker;
    private SculptEditListener editListener;
    private SculptControlsListener controlsListener;
    private SculptChunkListener chunkListener;
    private HeadResolver headResolver;

    // ---- wand selection tool ---------------------------------------------
    private WandListener wandListener;
    private SculptPasteHandler pasteHandler;

    // ---- fill converter --------------------------------------------------
    private FillConverter fillConverter;

    // ---- blueprint system ------------------------------------------------
    private BlueprintManager blueprintManager;

    // ---- observable startup state ----------------------------------------
    private volatile RuntimeHealth runtimeHealth = RuntimeHealth.loading(2);

    // ---- active SculptBlock map ------------------------------------------
    private final Map<BlockPosKey, SculptBlock> activeBlocks = new ConcurrentHashMap<>();
    private final Object activeBlocksLock = new Object();
    /** Slots promised to preflighted region operations but not registered yet. */
    private int reservedActiveBlockSlots;

    // ---- language manager ------------------------------------------------
    private LanguageManager languageManager;

    // ---- PDC flush scheduler ---------------------------------------------
    /** Cancellable handle for the repeating PDC-flush task (Folia or Bukkit). */
    private Object pdcFlushTask = null;

    // ---- Async registry loading ------------------------------------------
    /** Future that completes on the main thread when all registries are loaded. */
    private CompletableFuture<Void> registryLoadFuture;
    // ---- Bake pending tracking -------------------------------------------
    /**
     * Maps a local bake batch to the SculptBlocks waiting for it to finish.
     * Entries are inserted by {@link RegistryHeadResolver#headFor} when it
     * triggers a bake, and drained by the bake callback — avoiding an O(N)
     * scan of all active blocks on every bake completion.
     */
    private final Map<BlockBaker.Batch, Set<SculptBlock>> pendingBakeBlocks
            = new ConcurrentHashMap<>();

    /**
     * Register a SculptBlock as waiting for a local bake batch to complete.
     * Called from RegistryHeadResolver.headFor() when it triggers a bake.
     */
    void registerPendingBake(final BlockBaker.Batch batch, final SculptBlock block) {
        pendingBakeBlocks.computeIfAbsent(batch, ignored -> ConcurrentHashMap.newKeySet())
            .add(block);
    }

    /**
     * Reject delayed registry/bake results for an unloaded or superseded
     * SculptBlock. A chunk reload reconstructs a new instance at the same
     * position, while the old instance may still be retained by an in-flight
     * MineSkin request.
     */
    private boolean isCurrentAsyncRefreshTarget(final SculptBlock block) {
        return !disabling
            && block != null
            && activeBlocks.get(BlockPosKey.of(block.pos)) == block
            && block.canRefreshDisplays();
    }

    // ========================================================================
    //  Lifecycle
    // ========================================================================

    @Override
    public void onEnable() {
        this.disabling = false;

        // ---- 1. Config ---------------------------------------------------
        saveDefaultConfig();
        mergeMissingDefaults();
        reloadConfig();
        this.config = SculptConfig.from(getConfig());
        this.startupConfig = config;
        this.runtimeHealth = RuntimeHealth.loading(config.chunkGridSize());
        getLogger().log(Level.INFO, "[Sculpt] enabling (version {0})",
                getDescription().getVersion());
        getLogger().log(Level.INFO, "[Sculpt] config loaded (defaultGridSize={0})",
                config.chunkGridSize());

        this.dataDir = getDataFolder().toPath();
        final Path cacheRoot = dataDir.resolve("cache");
        final Path headsRoot = dataDir.resolve("heads");
        final String mcVersion = PlatformDetector.minecraftVersion();

        try {
            Files.createDirectories(cacheRoot);
            Files.createDirectories(headsRoot);
        } catch (final IOException e) {
            getLogger().log(Level.WARNING, "[Sculpt] failed to create data directory", e);
        }

        // ---- 1a. Language system ------------------------------------------
        this.languageManager = new LanguageManager(this);
        this.languageManager.initialize();
        MessageUtil.init(this.languageManager);

        this.registryExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Sculpt-Registry-Loader");
            thread.setDaemon(true);
            return thread;
        });

        this.textRenderExecutor = Executors.newFixedThreadPool(2, runnable -> {
            final Thread thread = new Thread(runnable, "Sculpt-TextDisplay-Renderer");
            thread.setDaemon(true);
            return thread;
        });
        this.visualShapeExecutor = Executors.newFixedThreadPool(2, runnable -> {
            final Thread thread = new Thread(runnable, "Sculpt-Visual-Shape-Resolver");
            thread.setDaemon(true);
            return thread;
        });
        final McAssetClient runtimeAssets = new McAssetClient(cacheRoot.resolve("assets"));
        this.textDisplayTextureCache = new TextDisplayTextureCache(
            runtimeAssets, mcVersion,
            getLogger(), textRenderExecutor);
        this.textDisplayBlockRenderer = new TextDisplayBlockRenderer(
            this, textDisplayTextureCache, textRenderExecutor,
            config.textDisplayMaxEntitiesPerBlock());
        this.visualShapeCache = new BlockVisualShapeCache(
            new BlockVisualShapeResolver(runtimeAssets, mcVersion, getLogger()),
            visualShapeExecutor);

        // ---- 2. Load stores + registries (ASYNC — does not block main thread) -
        this.registryLoadFuture = launchRegistryLoad(cacheRoot, mcVersion);

        // ---- 4. Non-bakeable block list (fast, no registry dependency) ----
        this.nonBakeableBlocks = new NonBakeableBlocks(this.dataDir, getLogger());

        // ---- 6. Event listeners (registered now; null-guarded in handlers) -
        // headResolver is null until the async load completes — event handlers
        // that need it check for null and return early.
        this.buildChecker = new BlockPlaceBuildChecker();
        getServer().getPluginManager().registerEvents(buildChecker, this);
        this.editListener = new SculptEditListener(
                this, null, new SculptBlockRegistryImpl(), buildChecker);
        getServer().getPluginManager().registerEvents(editListener, this);
        this.wandListener = new WandListener(this);
        getServer().getPluginManager().registerEvents(wandListener, this);
        this.fillConverter = new FillConverter(this);
        this.controlsListener = new SculptControlsListener(this);
        getServer().getPluginManager().registerEvents(controlsListener, this);
        this.chunkListener = new SculptChunkListener(this);
        getServer().getPluginManager().registerEvents(chunkListener, this);
        getServer().getPluginManager().registerEvents(
                new SculptPlayerListener(this), this);
        getServer().getPluginManager().registerEvents(
                new HeadBrowserListener(), this);

        // ---- 6a. WorldEdit integration ------------------------------------
        if (getServer().getPluginManager().isPluginEnabled("WorldEdit")
                || getServer().getPluginManager().isPluginEnabled("FastAsyncWorldEdit")) {
            try {
                pasteHandler = new SculptPasteHandler(this);
                getServer().getPluginManager().registerEvents(pasteHandler, this);
                pasteHandler.registerWorldEditHandler();
            } catch (final LinkageError | RuntimeException e) {
                getLogger().warning("[Sculpt] Failed to enable WorldEdit integration: "
                        + e.getMessage());
            }
        }

        // ---- 7. Commands (registered now; access registries lazily) -------
        this.blueprintManager = new BlueprintManager(this);
        final SculptModeCommand modeCmd = new SculptModeCommand(this);
        final SculptFillCommand fillCmd = new SculptFillCommand(this);
        final SculptDisplayCommand displayCmd = new SculptDisplayCommand(this);
        final SculptWandCommand wandCmd = new SculptWandCommand(this, blueprintManager);
        final SculptBlueprintCommand bpCmd = new SculptBlueprintCommand(this);
        final SculptHeadsCommand headsCmd = new SculptHeadsCommand(this);
        final SculptCommand cmd = new SculptCommand(
            this, modeCmd, fillCmd, displayCmd, wandCmd, bpCmd, headsCmd);
        final var command = getCommand("sculpt");
        if (command != null) {
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        } else {
            getLogger().severe("[Sculpt] /sculpt command not defined in plugin.yml");
        }
        // ---- 8. PDC flush scheduler ---------------------------------------
        schedulePdcFlush();

        // Phase 3 (bake pipeline), Phase 5 (headResolver), and Phase 9
        // (reload scan) are deferred to initializeRegistriesAndBakers(),
        // which runs on the main thread once the async load completes.
    }

    // ========================================================================
    //  Async registry loading
    // ========================================================================

    /**
     * Launch the heavy store + registry loading on a background thread.
     * Returns a future that completes on the global scheduler after
     * {@link #initializeRegistriesAndBakers} finishes.
     */
    private CompletableFuture<Void> launchRegistryLoad(
            final Path cacheRoot, final String mcVersion) {
        return CompletableFuture.supplyAsync(() -> {
            final List<HeadsStore> loadedStores = new ArrayList<>();
            final Map<Integer, HeadsRegistry> loadedRegistries = new HashMap<>();
            final long t0 = System.nanoTime();
            try {
                BlockTintReader.prepareColormaps(
                    new McAssetClient(cacheRoot.resolve("assets")), mcVersion, getLogger());

                for (final int g : TEXTURED_GRIDS) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException();
                    }
                    final SqliteHeadsStore runtimeStore = new SqliteHeadsStore(
                            getLogger(), cacheRoot.resolve("heads.sqlite"), g,
                            mcVersion, "sculpt-runtime");

                    HeadsStore installed = loadInstalledSbhCatalog(g);
                    final LayeredHeadsStore store = new LayeredHeadsStore(runtimeStore, installed);
                    loadedStores.add(store);

                    final HeadsRegistry regG = HeadsRegistry.loadFrom(
                            getLogger(), store, g, mcVersion, 4096, registryExecutor);
                    loadedRegistries.put(g, regG);

                    if (!regG.knownBlockKeys().isEmpty()) {
                        getLogger().info("[Sculpt] gridN=" + g + " - registry loaded ("
                                + regG.knownBlockKeys().size() + " blocks)");
                    }
                }
                final long elapsed = System.nanoTime() - t0;
                getLogger().info("[Sculpt] registry loading completed in "
                        + (elapsed / 1_000_000) + "ms (async)");
                return new RegistryLoadResult(loadedStores, loadedRegistries);
            } catch (RuntimeException | Error error) {
                closeStores(loadedStores);
                throw error;
            }
        }, registryExecutor).thenAcceptAsync(result -> {
                if (disabling || !isEnabled()) {
                    closeStores(result.stores());
                    return;
                }
                allStores.addAll(result.stores());
                headsRegistries.putAll(result.registries());
                initializeRegistriesAndBakers();
        }, dev.twme.sculpt.util.FoliaScheduler.globalExecutor(this)
        ).exceptionally(ex -> {
            final Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (!disabling && !(cause instanceof java.util.concurrent.CancellationException)) {
                runtimeHealth = RuntimeHealth.failed(config.chunkGridSize(), cause);
                getLogger().log(Level.SEVERE, "[Sculpt] async registry loading failed", ex);
            }
            return null;
        });
    }

    private HeadsStore loadInstalledSbhCatalog(final int gridN) {
        final Path path = dataDir.resolve("heads")
                .resolve("heads-" + gridN + SbFormat.FILE_EXTENSION);
        if (!Files.isRegularFile(path)) return null;
        try {
            final SbReader reader = SbReader.fromFile(path);
            if (reader.gridN() != gridN) {
                reader.close();
                getLogger().warning("[Sculpt] skipping " + path.getFileName()
                        + ": contains gridN=" + reader.gridN() + ", expected " + gridN);
                return null;
            }
            getLogger().info("[Sculpt] gridN=" + gridN + " - loaded "
                    + reader.blockCount() + " blocks from " + path.getFileName());
            return new SbGridStore(reader);
        } catch (final IOException e) {
            getLogger().warning("[Sculpt] failed to read " + path + ": " + e.getMessage());
            return null;
        }
    }

    private record RegistryLoadResult(
        List<HeadsStore> stores, Map<Integer, HeadsRegistry> registries) {}

    private void closeStores(Collection<HeadsStore> stores) {
        for (HeadsStore store : stores) {
            try { store.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Runs on the global scheduler after the async registry load completes.
     * Sets up the bake pipeline, head resolver, and processes any chunks
     * that loaded before the registries were ready.
     */
    private void initializeRegistriesAndBakers() {
        final long t0 = System.nanoTime();

        // ---- 3. Bake pipeline (optional, needs MineSkin API key) ----------
        String apiKey = config.mineskinApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            final String envKey = System.getenv("MINESKIN_API_KEY");
            if (envKey != null && !envKey.isBlank()) apiKey = envKey;
        }
        String apiUrl = config.mineskinApiUrl();
        final String envUrl = System.getenv("MINESKIN_API_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            apiUrl = SkinUploader.normalizeApiUrl(envUrl);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            this.uploader = new SkinUploader(getLogger(),
                    "Sculpt/" + getDescription().getVersion(),
                    apiKey,
                    apiUrl,
                    config.skinUploadBatchDelayMs());
            getLogger().info("[Sculpt] MineSkin client configured (url="
                    + apiUrl + ")");
            final Path cacheRoot = dataDir.resolve("cache");
            final Path skinCacheFile = cacheRoot.resolve("skins.json");
            final Path pngDir = cacheRoot.resolve("pngs");
            this.diskCache = new SkinDiskCache(getLogger(), skinCacheFile);
            this.bakerExecutor = Executors.newFixedThreadPool(2);
            final McAssetClient assetClient =
                    new McAssetClient(cacheRoot.resolve("assets"));
            final String mcVer = PlatformDetector.minecraftVersion();
            for (final int g : TEXTURED_GRIDS) {
                final HeadsRegistry regG = headsRegistries.get(g);
                if (regG == null) continue;
                bakers.put(g, new BlockBaker(
                        getLogger(),
                        () -> config.debug(),
                        assetClient,
                        mcVer,
                        regG,
                        uploader,
                        diskCache,
                        pngDir,
                        bakerExecutor,
                        config.skinUploadTimeoutMinutes(),
                        (completedBatch, succeeded) -> {
                            final var waitingBlocks = pendingBakeBlocks.remove(completedBatch);
                            if (waitingBlocks == null || disabling || !succeeded) return;
                            for (final SculptBlock sb : waitingBlocks) {
                                dev.twme.sculpt.util.FoliaScheduler.runRegionTask(
                                    Sculpt.this, sb.pos, () -> {
                                        if (isCurrentAsyncRefreshTarget(sb)) {
                                            sb.reRender();
                                        }
                                    });
                            }
                        }));
                getLogger().log(Level.INFO, "[Sculpt] baker ready for gridN={0}", g);
            }
        } else {
            this.uploader = null;
            this.diskCache = null;
            this.bakerExecutor = null;
            getLogger().info("[Sculpt] bake pipeline disabled"
                    + " — set runtimeBaking.mineskin.apiKey in config.yml");
        }

        // ---- 5. HeadResolver ----------------------------------------------
        this.headResolver = HeadResolver.fromRegistry(headsRegistries, bakers,
                this::registerPendingBake,
                (block, refresh) -> dev.twme.sculpt.util.FoliaScheduler.runRegionTask(
                    Sculpt.this, block.pos, () -> {
                        try {
                            refresh.run();
                            if (isCurrentAsyncRefreshTarget(block)) {
                                block.reRender();
                            }
                        } catch (RuntimeException ignored) {
                            // The block may have been cleared between I/O
                            // completion and the owning region task.
                        }
                    }));
        updateRuntimeHealth(true);

        // Update the edit listener's headResolver reference
        // (registered in onEnable with null; now set to the real resolver)
        if (this.editListener != null) {
            this.editListener.setHeadResolver(headResolver);
        }

        // ---- 9. Deferred reload scan (each chunk runs on its owning region) -
        if (chunkListener != null) {
            chunkListener.scheduleLoadedChunkReconciliation();
        }

        final long elapsed = System.nanoTime() - t0;
        getLogger().log(Level.INFO,
                "[Sculpt] registries + bake pipeline ready in {0}ms", elapsed / 1_000_000);
    }

    private void updateRuntimeHealth(final boolean logResult) {
        final HeadsRegistry configured = headsRegistries.get(config.chunkGridSize());
        final int configuredBlocks = configured == null
            ? 0 : configured.knownBlockKeys().size();
        final int totalBlocks = headsRegistries.values().stream()
            .mapToInt(registry -> registry.knownBlockKeys().size())
            .sum();
        final boolean configuredGridCanBake = config.chunkGridSize() > 1
            && bakers.containsKey(config.chunkGridSize());
        runtimeHealth = RuntimeHealth.evaluate(config.chunkGridSize(), configuredBlocks,
            totalBlocks, configuredGridCanBake);
        if (!logResult) return;

        if (runtimeHealth.status() == RuntimeHealth.Status.DEGRADED) {
            getLogger().warning("[Sculpt] DEGRADED: no head data is available for gridN="
                + config.chunkGridSize() + " and runtime baking is disabled. HEAD-rendered "
                + "cells will use placeholders; TEXTDISPLAY remains available and AUTO "
                + "keeps missing opaque heads on TextDisplay. "
                + "Install heads/heads-" + config.chunkGridSize()
                + ".sbh or configure runtimeBaking.mineskin.apiKey for HEAD mode.");
        } else {
            getLogger().info("[Sculpt] health=READY, configuredGrid="
                + config.chunkGridSize() + ", configuredBlocks=" + configuredBlocks
                + ", totalIndexedBlocks=" + totalBlocks
                + ", runtimeBake=" + configuredGridCanBake);
        }
    }

    // ========================================================================
    //  Shutdown
    // ========================================================================

    @Override
    public void onDisable() {
        getLogger().info("[Sculpt] disabling");
        disabling = true;

        if (pasteHandler != null) {
            pasteHandler.unregisterWorldEditHandler();
            pasteHandler = null;
        }

        if (controlsListener != null) {
            controlsListener.shutdown();
            controlsListener = null;
        }

        // Cancel the async registry load if still running
        if (registryLoadFuture != null) {
            registryLoadFuture.cancel(true);
        }

        if (registryExecutor != null) {
            registryExecutor.shutdownNow();
            try {
                if (!registryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    getLogger().warning("[Sculpt] registry loader did not stop within 5 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            registryExecutor = null;
        }

        // TextDisplay pixels are a derived, non-persistent cache. Remove them
        // during a normal Paper plugin disable so hot reloads cannot leave
        // stale pixels beside the copies reconstructed by the next enable.
        // Folia entity access remains region-confined, so chunk unload handles
        // those non-persistent pixels there instead.
        if (!dev.twme.sculpt.util.FoliaScheduler.isFolia()) {
            for (final SculptBlock block : activeBlocks.values()) {
                block.suspendTextRendering();
            }
        }

        if (textRenderExecutor != null) {
            textRenderExecutor.shutdownNow();
            textRenderExecutor = null;
        }
        if (textDisplayTextureCache != null) {
            textDisplayTextureCache.clear();
            textDisplayTextureCache = null;
        }
        textDisplayBlockRenderer = null;
        if (visualShapeCache != null) {
            visualShapeCache.clear();
            visualShapeCache = null;
        }
        if (visualShapeExecutor != null) {
            visualShapeExecutor.shutdownNow();
            visualShapeExecutor = null;
        }

        if (pdcFlushTask != null) {
            dev.twme.sculpt.util.FoliaScheduler.cancelTask(pdcFlushTask);
            pdcFlushTask = null;
        }
        if (!dev.twme.sculpt.util.FoliaScheduler.isFolia()) {
            flushDirtyPDC();
        }

        // 保留實體（不移除），ChunkLoad 時會重建 activeBlocks
        clearActiveBlocks();

        // Shutdown the bake executor
        if (bakerExecutor != null) {
            bakerExecutor.shutdown();
            try {
                if (!bakerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    bakerExecutor.shutdownNow();
                }
            } catch (final InterruptedException e) {
                bakerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Close all heads stores (releases zip file handles)
        closeStores(allStores);
        allStores.clear();
        headsRegistries.clear();
        bakers.clear();
        pendingBakeBlocks.clear();
        if (chunkListener != null) chunkListener.clearPendingChunks();

        clearActiveBlocks();
    }

    // ========================================================================
    //  Language
    // ========================================================================

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    // ========================================================================
    //  Config accessors
    // ========================================================================

    public SculptConfig sculptConfig() {
        return config;
    }

    public RuntimeHealth runtimeHealth() {
        return runtimeHealth;
    }

    /**
     * Merge any keys from the bundled {@code config.yml} that are missing from
     * the on-disk config file.  This ensures that when a plugin update adds
     * new configuration options, server admins automatically get the new keys
     * with their default values — no manual copy-paste or file recreation
     * needed.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>Known legacy paths are migrated before defaults are merged.
     *   <li>Only <em>leaf</em> keys (not configuration sections) are merged.
     *   <li>Existing user values are never overwritten.
     *   <li>Files from a newer schema version are left unchanged.
     *   <li>If any keys were added, the on-disk file is saved immediately.
     * </ul>
     */
    private void mergeMissingDefaults() {
        // Load the bundled config.yml from the JAR
        final YamlConfiguration jarDefaults = new YamlConfiguration();
        try (InputStream in = getResource("config.yml")) {
            if (in == null) return;
            jarDefaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final Exception e) {
            getLogger().warning("[Sculpt] Failed to load bundled config.yml for merge: "
                    + e.getMessage());
            return;
        }

        // Load the current on-disk config
        reloadConfig();
        final FileConfiguration current = getConfig();
        final int sourceVersion = current.contains("configVersion", true)
            ? current.getInt("configVersion") : 0;
        if (sourceVersion > SculptConfigMigrator.CURRENT_VERSION) {
            getLogger().warning("[Sculpt] config.yml uses newer schema version "
                + sourceVersion + "; leaving it unchanged");
            return;
        }
        final boolean migrated = SculptConfigMigrator.migrate(current);

        // Compare leaf keys between bundled default and on-disk config
        boolean changed = migrated;
        changed |= YamlMigrationSupport.mergeMissingLeaves(current, jarDefaults);

        if (changed) {
            saveConfig();
            getLogger().info(migrated
                ? "[Sculpt] Migrated config.yml to schema version "
                    + SculptConfigMigrator.CURRENT_VERSION + " and merged new defaults"
                : "[Sculpt] Auto-merged new configuration keys from bundled config.yml");
        }
    }

    public void reloadSculptConfig() {
        final SculptConfig previous = this.config;
        saveDefaultConfig();
        mergeMissingDefaults();
        reloadConfig();
        this.config = SculptConfig.from(getConfig());
        if (previous != null && runtimeBakeSettingsChanged(startupConfig, config)) {
            getLogger().warning("[Sculpt] runtimeBaking settings changed; restart the server"
                + " to rebuild the MineSkin uploader and texture bakers");
        }
        if (previous != null
                && startupConfig.textDisplayMaxEntitiesPerBlock()
                    != config.textDisplayMaxEntitiesPerBlock()) {
            getLogger().warning("[Sculpt] rendering.textDisplay.maxEntitiesPerBlock"
                + " changed; restart the server to rebuild the TextDisplay renderer");
        }
        if (headResolver != null) updateRuntimeHealth(false);
        schedulePdcFlush();
        if (blueprintManager != null) {
            blueprintManager.reloadConfig();
        }
        getLogger().log(Level.INFO, "[Sculpt] config reloaded (defaultGridSize={0})",
                config.chunkGridSize());
        // Also reload the non-bakeable block list from disk
        if (this.dataDir != null) {
            this.nonBakeableBlocks = new NonBakeableBlocks(dataDir, getLogger());
        }
        // Reload language system (settings may have changed)
        if (this.languageManager != null) {
            this.languageManager.reload();
        }
    }

    private void schedulePdcFlush() {
        if (pdcFlushTask != null) {
            dev.twme.sculpt.util.FoliaScheduler.cancelTask(pdcFlushTask);
        }
        long flushPeriod = Math.max(20L, config.autoSaveIntervalSeconds() * 20L);
        pdcFlushTask = dev.twme.sculpt.util.FoliaScheduler.runGlobalTaskTimer(
            this, this::flushDirtyPDC, flushPeriod, flushPeriod);
    }

    private static boolean runtimeBakeSettingsChanged(
            SculptConfig previous, SculptConfig current) {
        return !previous.mineskinApiKey().equals(current.mineskinApiKey())
            || !previous.mineskinApiUrl().equals(current.mineskinApiUrl())
            || previous.skinUploadBatchDelayMs() != current.skinUploadBatchDelayMs()
            || previous.skinUploadTimeoutMinutes() != current.skinUploadTimeoutMinutes();
    }

    // ========================================================================
    //  Per-player grid size
    // ========================================================================

    private final PlayerRuntimeState playerRuntimeState = new PlayerRuntimeState();

    public int gridSizeFor(final Player player) {
        return playerRuntimeState.gridSize(
                player.getUniqueId(), config.chunkGridSize());
    }

    public void setGridSizeFor(final Player player, final int gridN) {
        playerRuntimeState.setGridSize(player.getUniqueId(), gridN);
    }

    /**
     * Cycle the player's grid size to the next allowed value (1→2→4→8→16→1).
     * Skips grid sizes the player doesn't have permission for.
     *
     * @return the new grid size, or the current one if no other allowed
     */
    public int cycleGridSize(final Player player) {
        final int current = gridSizeFor(player);
        final int[] ALL_GRIDS = {1, 2, 4, 8, 16};
        int next = -1;
        // Find the next larger allowed grid
        for (final int g : ALL_GRIDS) {
            if (g > current && SculptCommand.canUseGrid(player, g)) {
                next = g;
                break;
            }
        }
        // Wrap around to the smallest allowed grid
        if (next == -1) {
            for (final int g : ALL_GRIDS) {
                if (SculptCommand.canUseGrid(player, g)) {
                    next = g;
                    break;
                }
            }
        }
        if (next == -1 || next == current) return current;
        setGridSizeFor(player, next);
        return next;
    }

    // ========================================================================
    //  Hover preview toggle (per-player)
    // ========================================================================

    /**
     * Per-player explicit hover toggle.  {@code null} = use permission-based default.
     */
    /**
     * Whether hover preview is enabled for the given player.
     * <p>If the player has explicitly toggled hover via {@code /sculpt preview},
     * that explicit state is returned.  Otherwise the fallback is the
     * {@code sculpt.use.preview.auto} permission node.
     */
    public boolean isHoverEnabled(final Player player) {
        final Boolean explicit = playerRuntimeState.hoverState(player.getUniqueId());
        if (explicit != null) return explicit;
        return player.hasPermission(SculptPermissions.USE_PREVIEW_AUTO);
    }

    /**
     * Set the explicit hover preview state for the given player.
     * Pass {@code null} to reset to the permission-based default.
     */
    public void setHoverEnabled(final Player player, final Boolean state) {
        playerRuntimeState.setHoverState(player.getUniqueId(), state);
    }

    /**
     * Toggle the hover preview for the given player.
     * @return the new state
     */
    public boolean toggleHover(final Player player) {
        final boolean next = !isHoverEnabled(player);
        setHoverEnabled(player, next);
        return next;
    }

    // ========================================================================
    //  SculptMode state (retained across reconnects for this server process)
    // ========================================================================

    public boolean isSculptMode(final Player player) {
        return playerRuntimeState.sculptMode(player.getUniqueId());
    }

    public void setSculptMode(final Player player, final boolean mode) {
        playerRuntimeState.setSculptMode(player.getUniqueId(), mode);
        playerRuntimeState.setSculptModeSuspended(player.getUniqueId(), false);
        if (controlsListener != null) controlsListener.clearPending(player);
        if (!mode && editListener != null) editListener.endSession(player);
    }

    public void toggleSculptMode(final Player player) {
        setSculptMode(player, !isSculptMode(player));
    }

    public boolean isSculptModeSuspended(final Player player) {
        return playerRuntimeState.sculptModeSuspended(player.getUniqueId());
    }

    public boolean isSculptModeActive(final Player player) {
        return isSculptMode(player) && !isSculptModeSuspended(player);
    }

    public void setSculptModeSuspended(
            final Player player,
            final boolean suspended) {
        playerRuntimeState.setSculptModeSuspended(
            player.getUniqueId(), suspended);
        if (controlsListener != null) controlsListener.clearPending(player);
        if (suspended && editListener != null) editListener.endSession(player);
    }

    public boolean toggleSculptModeSuspended(final Player player) {
        final boolean suspended = !isSculptModeSuspended(player);
        setSculptModeSuspended(player, suspended);
        return isSculptModeSuspended(player);
    }

    // ========================================================================
    //  Fill/display choices (retained across reconnects for this server process)
    // ========================================================================

    public FillMode fillModeFor(final Player player) {
        return playerRuntimeState.fillMode(
                player.getUniqueId(), config.defaultFillMode());
    }

    public void setFillMode(final Player player, final FillMode mode) {
        playerRuntimeState.setFillMode(player.getUniqueId(), mode);
    }

    public SculptDisplayMode displayModeFor(final Player player) {
        return playerRuntimeState.displayMode(
                player.getUniqueId(), config.defaultDisplayMode());
    }

    public void setDisplayMode(
            final Player player,
            final SculptDisplayMode mode) {
        playerRuntimeState.setDisplayMode(player.getUniqueId(), mode);
    }

    /** Compatibility helper while collision-specific integrations migrate. */
    public boolean isShulkerMode(final Player player) {
        return fillModeFor(player) == FillMode.SHULKER;
    }

    /** Compatibility helper while collision-specific integrations migrate. */
    public void setShulkerMode(final Player player, final boolean mode) {
        setFillMode(player, mode ? FillMode.SHULKER : FillMode.BARRIER);
    }

    void clearPlayerTransientState(final java.util.UUID playerId) {
        playerRuntimeState.clearTransient(playerId);
    }

    // ========================================================================
    //  Held BlockData helper (used by SculptMode)
    // ========================================================================

    /**
     * 從玩家主手取得 BlockData。
     * - 方塊物品有 BlockDataMeta → 回傳物品儲存的完整 BlockData（保留朝向等狀態）
     * - 方塊物品無 BlockDataMeta → 回傳該材質的預設 BlockData
     * - 非方塊物品或空手 → 回傳 null
     */
    public BlockData heldBlockData(final Player player) {
        final ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) return null;
        final Material mat = item.getType();
        if (!mat.isBlock() || !mat.isSolid()) return null;
        final ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockDataMeta bdm) return bdm.getBlockData(mat);
        return mat.createBlockData();
    }

    /**
     * Resolve the material placed by Sculpt mode. Player heads are allowed even
     * though Bukkit does not classify them as solid; only their already present
     * {@code textures} property is copied, so this method never performs a
     * profile/network lookup.
     */
    public CellMaterial heldCellMaterial(final Player player) {
        final ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) return null;
        if (item.getType() != Material.PLAYER_HEAD) {
            final BlockData data = heldBlockData(player);
            return data == null ? null : CellMaterial.block(data);
        }

        final ItemMeta meta = item.getItemMeta();
        final BlockData data = meta instanceof BlockDataMeta blockDataMeta
            ? blockDataMeta.getBlockData(Material.PLAYER_HEAD)
            : Material.PLAYER_HEAD.createBlockData();
        PlayerHeadTexture texture = null;
        if (meta instanceof SkullMeta skullMeta) {
            final com.destroystokyo.paper.profile.PlayerProfile profile =
                skullMeta.getPlayerProfile();
            if (profile != null) {
                for (final com.destroystokyo.paper.profile.ProfileProperty property
                        : profile.getProperties()) {
                    if (!"textures".equals(property.getName())
                            || property.getValue().isBlank()) continue;
                    try {
                        texture = new PlayerHeadTexture(
                            property.getValue(), property.getSignature());
                    } catch (final IllegalArgumentException ignored) {
                        // Treat malformed/oversized profile data as an untextured head.
                    }
                    break;
                }
            }
        }
        return new CellMaterial(data, texture);
    }

    /**
     * Show a full-grid preview overlay. Stub retained for SculptCommand
     * compatibility; the ReDesign v2 uses SelectionHighlight/PreviewHighlight
     * automatically via the hover system.
     */
    public void showFullGridPreview(final Player viewer, final Location blockPos,
                                     final int gridN, final Quaternionf blockRotation) {
        // v2: PreviewHighlight handles this automatically on hover
    }

    // ========================================================================
    //  Registry accessors
    // ========================================================================

    public HeadsRegistry getHeadsRegistry() {
        return headsRegistries.get(config.chunkGridSize());
    }

    public HeadsRegistry getHeadsRegistry(final int gridN) {
        return headsRegistries.get(gridN);
    }

    public BlockBaker getBlockBaker() {
        return getBlockBaker(config.chunkGridSize());
    }

    public BlockBaker getBlockBaker(final int gridN) {
        return bakers.get(gridN);
    }

    public HeadResolver getHeadResolver() {
        return headResolver;
    }

    boolean isHeadResolverReady() {
        return headResolver != null;
    }

    public boolean isDisabling() {
        return disabling;
    }

    public TextBlockRenderer getTextBlockRenderer() {
        return textDisplayBlockRenderer;
    }

    public BlockVisualShapeCache getVisualShapeCache() {
        return visualShapeCache;
    }

    public BlueprintManager getBlueprintManager() {
        return blueprintManager;
    }

    public WandListener getWandListener() {
        return wandListener;
    }

    public FillConverter getFillConverter() {
        return fillConverter;
    }

    public boolean canPlayerBuild(final Player player, final Block block) {
        return buildChecker != null && buildChecker.canBuild(player, block);
    }

    public boolean canPlayerBuild(final Player player, final Block block,
                                  final ItemStack itemInHand) {
        return buildChecker != null && buildChecker.canBuild(player, block, itemInHand);
    }

    public boolean isNonBakeable(final Material material) {
        return nonBakeableBlocks != null && nonBakeableBlocks.isNonBakeable(material);
    }

    public boolean isMaterialSupported(
            final Material material,
            final SculptDisplayMode displayMode) {
        if (!isNonBakeable(material)) return true;
        return materialSupportStatus(material, displayMode, true)
            .allowsOperation();
    }

    /**
     * Non-loading form for tab completion and other enumeration hot paths.
     * Unknown non-bakeable models remain hidden until an actual operation has
     * requested their model.
     */
    public boolean isMaterialSupportedIfKnown(
            final Material material,
            final SculptDisplayMode displayMode) {
        if (!isNonBakeable(material)) return true;
        return materialSupportStatus(material, displayMode, false)
            == TextDisplayMaterialSupport.Status.SUPPORTED;
    }

    private TextDisplayMaterialSupport.Status materialSupportStatus(
            final Material material,
            final SculptDisplayMode displayMode,
            final boolean load) {
        if (displayMode == null || !displayMode.usesTextRenderer()
                || textDisplayTextureCache == null) {
            return TextDisplayMaterialSupport.Status.UNSUPPORTED;
        }
        return textDisplayTextureCache.materialSupport(material, load);
    }

    // ========================================================================
    //  Active block management
    // ========================================================================

    public Collection<SculptBlock> getActiveBlocks() {
        return List.copyOf(activeBlocks.values());
    }

    public SculptBlock getActiveBlock(final BlockPosKey key) {
        return activeBlocks.get(key);
    }

    /**
     * Register a newly-created block while enforcing the configured active limit.
     * Existing persisted blocks use the reconstruction path and are never deleted
     * merely because an administrator lowered the limit.
     */
    public boolean registerSculptBlock(final BlockPosKey key, final SculptBlock block) {
        synchronized (activeBlocksLock) {
            final SculptBlock current = activeBlocks.get(key);
            if (current != null) return current == block;
            final int limit = config.maxActiveSculptBlocks();
            if (limit > 0
                    && activeBlocks.size() + reservedActiveBlockSlots >= limit) {
                return false;
            }
            activeBlocks.put(key, block);
            return true;
        }
    }

    /**
     * Atomically reserve capacity for a preflighted multi-block operation.
     * Ordinary registrations account for the reservation until it is consumed
     * or closed, so a later apply phase cannot partially fail due to a race.
     */
    public ActiveBlockReservation reserveSculptBlockSlots(final int requested) {
        if (requested < 0) throw new IllegalArgumentException("requested slots must be non-negative");
        synchronized (activeBlocksLock) {
            final int limit = config.maxActiveSculptBlocks();
            if (limit > 0
                    && (long) activeBlocks.size() + reservedActiveBlockSlots + requested > limit) {
                return null;
            }
            reservedActiveBlockSlots += requested;
            return new ActiveBlockReservation(requested);
        }
    }

    /** Atomically replace a registered block without consuming another slot. */
    public boolean replaceSculptBlock(final BlockPosKey key, final SculptBlock expected,
                                      final SculptBlock replacement) {
        synchronized (activeBlocksLock) {
            if (activeBlocks.get(key) != expected) return false;
            activeBlocks.put(key, replacement);
            return true;
        }
    }

    /** Restore a previously occupied slot after a failed world mutation. */
    public boolean restoreSculptBlock(final BlockPosKey key, final SculptBlock block) {
        synchronized (activeBlocksLock) {
            if (activeBlocks.containsKey(key)) return false;
            activeBlocks.put(key, block);
            return true;
        }
    }

    public void unregisterSculptBlock(final BlockPosKey key) {
        synchronized (activeBlocksLock) {
            activeBlocks.remove(key);
        }
    }

    /** Remove only the expected instance, protecting a newer replacement. */
    public void unregisterSculptBlock(final BlockPosKey key, final SculptBlock expected) {
        synchronized (activeBlocksLock) {
            activeBlocks.remove(key, expected);
        }
    }

    private void registerReconstructedSculptBlock(final BlockPosKey key,
                                                   final SculptBlock block) {
        synchronized (activeBlocksLock) {
            activeBlocks.putIfAbsent(key, block);
        }
    }

    private void clearActiveBlocks() {
        synchronized (activeBlocksLock) {
            activeBlocks.clear();
            reservedActiveBlockSlots = 0;
        }
    }

    public final class ActiveBlockReservation implements AutoCloseable {
        private int remaining;
        private boolean closed;

        private ActiveBlockReservation(final int remaining) {
            this.remaining = remaining;
        }

        /** Consume one promised slot while registering the exact block. */
        public boolean register(final BlockPosKey key, final SculptBlock block) {
            synchronized (activeBlocksLock) {
                if (closed || remaining <= 0 || activeBlocks.containsKey(key)) {
                    return false;
                }
                activeBlocks.put(key, block);
                remaining--;
                reservedActiveBlockSlots = Math.max(0, reservedActiveBlockSlots - 1);
                return true;
            }
        }

        @Override
        public void close() {
            synchronized (activeBlocksLock) {
                if (closed) return;
                reservedActiveBlockSlots = Math.max(0,
                    reservedActiveBlockSlots - remaining);
                remaining = 0;
                closed = true;
            }
        }
    }

    // ========================================================================
    //  Chunk unload: remove blocks in that chunk from active map
    // ========================================================================

    public void unloadChunk(final Chunk chunk) {
        synchronized (activeBlocksLock) {
            activeBlocks.entrySet().removeIf(e -> {
                final Location p = e.getValue().pos;
                if (p.getWorld() != chunk.getWorld()) return false;
                return p.getBlockX() >> 4 == chunk.getX()
                    && p.getBlockZ() >> 4 == chunk.getZ();
            });
        }
    }

    // ========================================================================
    //  PDC flush
    // ========================================================================

    public void flushDirtyPDC() {
        for (final SculptBlock block : activeBlocks.values()) {
            if (dev.twme.sculpt.util.FoliaScheduler.isFolia()) {
                dev.twme.sculpt.util.FoliaScheduler.runRegionTask(this, block.pos,
                    () -> flushBlockPDC(block));
            } else {
                flushBlockPDC(block);
            }
        }
    }

    /** Flush pending PDC only for SculptBlocks inside a WorldEdit selection. */
    public void flushDirtyPDC(
            final String worldName,
            final int minX, final int minY, final int minZ,
            final int maxX, final int maxY, final int maxZ) {
        for (final SculptBlock block : activeBlocks.values()) {
            final Location position = block.pos;
            if (!position.getWorld().getName().equals(worldName)
                    || position.getBlockX() < minX || position.getBlockX() > maxX
                    || position.getBlockY() < minY || position.getBlockY() > maxY
                    || position.getBlockZ() < minZ || position.getBlockZ() > maxZ) {
                continue;
            }
            flushBlockPDC(block);
        }
    }

    /**
     * Flush models selected for a WE/FAWE copy and, when entities are being
     * copied, temporarily remove their regenerable TextDisplay pixels.
     * Keeping only the root model entity prevents clipboard size and copy
     * time from scaling with the rendered pixel count.
     */
    public List<SculptBlock> prepareClipboardCopy(
            final String worldName,
            final int minX, final int minY, final int minZ,
            final int maxX, final int maxY, final int maxZ,
            final boolean includeEntities) {
        final List<SculptBlock> suspended = new ArrayList<>();
        for (final SculptBlock block : activeBlocks.values()) {
            final Location position = block.pos;
            if (!position.getWorld().getName().equals(worldName)
                    || position.getBlockX() < minX || position.getBlockX() > maxX
                    || position.getBlockY() < minY || position.getBlockY() > maxY
                    || position.getBlockZ() < minZ || position.getBlockZ() > maxZ) {
                continue;
            }
            flushBlockPDC(block);
            if (includeEntities) {
                try {
                    if (block.suspendTextRendering()) suspended.add(block);
                } catch (final RuntimeException exception) {
                    // A partial cancellation still needs a resume attempt.
                    suspended.add(block);
                    getLogger().log(Level.WARNING,
                        "[Sculpt] failed to suspend TextDisplay rendering at "
                            + position, exception);
                }
            }
        }
        return List.copyOf(suspended);
    }

    /** Resume derived clipboard visuals on each block's owning region. */
    public void resumeClipboardTextRendering(
            final Collection<SculptBlock> suspended) {
        for (final SculptBlock block : suspended) {
            dev.twme.sculpt.util.FoliaScheduler.runRegionTask(
                this, block.pos, block::resumeTextRendering);
        }
    }

    private void flushBlockPDC(final SculptBlock block) {
        try {
            if (!block.despawned) block.flushPDC();
        } catch (final Exception e) {
            getLogger().log(Level.WARNING, "[Sculpt] failed to flush PDC at " + block.pos, e);
        }
    }

    /** Reconcile pasted Sculpt entities from an owning chunk-region task. */
    public void reconcilePastedEntities(final Chunk chunk) {
        if (chunkListener != null) chunkListener.reconcileChunk(chunk);
    }

    // ========================================================================
    //  Reconstruction from entity PDC (ReDesign §6.1)
    // ========================================================================

    public boolean reconstructSculptBlock(final ItemDisplay rootDisplay) {
        // Guard: registries not yet loaded — can't resolve heads
        if (headResolver == null) return false;

        final PersistentDataContainer pdc = rootDisplay.getPersistentDataContainer();
        final Location pos = rootDisplay.getLocation().toBlockLocation();
        final BlockPosKey key = BlockPosKey.of(pos);
        final SculptBlock active = activeBlocks.get(key);
        if (active != null) {
            if (active.rootEntity instanceof dev.twme.sculpt.transport.bukkit.BukkitDisplayHandle handle
                    && handle.entity() == rootDisplay) {
                return true;
            }
            rootDisplay.remove();
            return false;
        }

        final String blockDataStr = pdc.get(key("sculpt", "original_block"), PersistentDataType.STRING);
        final String variantStr = pdc.get(key("sculpt", "matched_variant"), PersistentDataType.STRING);
        final String rotationStr = pdc.get(key("sculpt", "rotation"), PersistentDataType.STRING);
        final String removedRaw = pdc.get(key("sculpt", "removed"), PersistentDataType.STRING);
        final String subdividedRaw = pdc.get(key("sculpt", "subdivided"), PersistentDataType.STRING);

        if (blockDataStr == null) {
            getLogger().warning("[Sculpt] root entity at " + pos
                    + " has no original_block — removing orphan");
            rootDisplay.remove();
            return false;
        }

        final org.bukkit.block.data.BlockData originalBlockData =
                org.bukkit.Bukkit.createBlockData(blockDataStr);
        final Quaternionf rotation = parseQuaternion(rotationStr);
        // Read persisted tint (stored as STRING via setPDC interface; defaults to 0 = untinted)
        int parsed = 0;
        try {
            String tintStr = pdc.get(key("sculpt", "tint_argb"), PersistentDataType.STRING);
            if (tintStr != null) parsed = Integer.parseInt(tintStr);
        } catch (NumberFormatException e) {
            // use default 0
        }
        final int tintArgb = parsed;

        final SculptBlock sculptBlock = new SculptBlock(
                pos.getWorld(), pos, originalBlockData,
                variantStr != null ? variantStr : "",
                rotation,
                new BukkitTransportSession(pos.getWorld()),
                headResolver, tintArgb);
        sculptBlock.setOnCleared(() -> unregisterSculptBlock(key, sculptBlock));
        // Rebuild data before resolving any heads. In particular, a custom
        // player-head block must load its stored profile before headFor runs,
        // otherwise reconstruction could request an irrelevant PLAYER_HEAD bake.
        sculptBlock.initLeavesDataOnly();
        final String legacyShulker = pdc.get(
            key("sculpt", "shulker_mode"), PersistentDataType.STRING);
        final String persistedFillRaw = pdc.get(
            key("sculpt", "fill_mode"), PersistentDataType.STRING);
        final String persistedDisplayRaw = pdc.get(
            key("sculpt", "display_mode"), PersistentDataType.STRING);
        final FillMode persistedFill = FillMode.parse(
            persistedFillRaw,
            "true".equals(legacyShulker) ? FillMode.SHULKER : FillMode.BARRIER);
        final SculptDisplayMode persistedDisplay = SculptDisplayMode.parse(
            persistedDisplayRaw, SculptDisplayMode.HEAD);
        sculptBlock.configureStrategies(
            persistedFill, persistedDisplay, textDisplayBlockRenderer);
        sculptBlock.rootEntity =
                new dev.twme.sculpt.transport.bukkit.BukkitDisplayHandle(rootDisplay);
        sculptBlock.session.track(sculptBlock.rootEntity);
        sculptBlock.state = SculptBlock.State.SCULPTED;
        if (persistedFillRaw == null || persistedDisplayRaw == null
                || legacyShulker != null) {
            sculptBlock.markPDCDirty();
        }

        // Rebuild subdivided branches (sorted by depth)
        if (subdividedRaw != null && !subdividedRaw.isEmpty()) {
            final String[] paths = subdividedRaw.split("\\|");
            java.util.Arrays.sort(paths,
                    java.util.Comparator.comparingInt(p -> p.split("\\.").length));
            for (final String path : paths) {
                final OctreeNode node = OctreeNode.fromPath(sculptBlock.root, path);
                if (node != null && node.isLeaf()) node.subdivide();
            }
        }

        // Mark removed leaves
        if (removedRaw != null && !removedRaw.isEmpty()) {
            for (final String path : removedRaw.split("\\|")) {
                OctreeNode node = OctreeNode.fromPath(sculptBlock.root, path);
                if (node == null) {
                    // Stale PDC: also ensure branch paths exist for removed nodes
                    node = ensureNodePath(sculptBlock.root, path);
                }
                if (node != null && node.isLeaf()) {
                    node.remove();
                }
            }
        }

        // SculptMode：還原 non-removed leaf 的 blockData 覆寫
        final String leafBlockDataRaw = pdc.get(
                key("sculpt", "leaf_block_data"), PersistentDataType.STRING);
        if (leafBlockDataRaw != null && !leafBlockDataRaw.isEmpty()) {
            for (final String entry : leafBlockDataRaw.split("\\|")) {
                final int eq = entry.indexOf('=');
                if (eq < 0) continue;
                final String leafPath = entry.substring(0, eq);
                final String leafBlockDataVal = entry.substring(eq + 1);
                final OctreeNode leafNode = OctreeNode.fromPath(sculptBlock.root, leafPath);
                if (leafNode != null && leafNode.isLeaf()) {
                    try {
                        leafNode.setBlockData(
                                org.bukkit.Bukkit.createBlockData(leafBlockDataVal));
                    } catch (final Exception ignored) {
                        // ignore corrupted data — fallback to originalBlockData
                    }
                }
            }
        }

        // Restore deduplicated profiles for both occupied and removed leaves.
        // Invalid optional texture data does not prevent the block itself from
        // loading; affected leaves fall back to their stored BlockData.
        final byte[] leafPlayerHeadsRaw = pdc.get(
            key("sculpt", "leaf_player_heads"), PersistentDataType.BYTE_ARRAY);
        try {
            PlayerHeadTextureCodec.apply(leafPlayerHeadsRaw, sculptBlock.root);
        } catch (final IllegalArgumentException exception) {
            getLogger().warning("[Sculpt] ignored invalid player-head cell data at "
                + pos + ": " + exception.getMessage());
        }
        sculptBlock.setMixed(sculptBlock.recomputeMixedState());
        sculptBlock.rebuildCollisionTopology();

        // Match passengers to leaves
        // IMPORTANT: if subdividedRaw PDC is stale (crash before timer flush),
        // fromPath may return null. Always subdivide intermediate nodes to ensure
        // the tree can accommodate all passenger paths.
        final NamespacedKey pathKey = key("sculpt", "path");
        final java.util.List<Entity> unmatched = new java.util.ArrayList<>();
        final java.util.List<TextDisplay> textPixels = new java.util.ArrayList<>();
        for (final Entity passenger : rootDisplay.getPassengers()) {
            if (passenger instanceof TextDisplay textDisplay) {
                final String type = textDisplay.getPersistentDataContainer().get(
                    key("sculpt", "type"), PersistentDataType.STRING);
                if (TextDisplayBlockRenderer.TEXT_PIXEL_TYPE.equals(type)) {
                    textPixels.add(textDisplay);
                } else {
                    textDisplay.remove();
                }
                continue;
            }
            if (!(passenger instanceof ItemDisplay leafDisplay)) {
                passenger.remove();
                continue;
            }
            final String path = leafDisplay.getPersistentDataContainer()
                    .get(pathKey, PersistentDataType.STRING);
            if (path == null) {
                unmatched.add(passenger);
                continue;
            }
            OctreeNode leaf = OctreeNode.fromPath(sculptBlock.root, path);
            if (leaf == null) {
                // Stale PDC: subdivide nodes along the path until we reach the leaf
                leaf = ensureNodePath(sculptBlock.root, path);
            }
            if (leaf == null || !leaf.isLeaf()) {
                leafDisplay.remove();
                continue;
            }
            if (leaf.isRemoved()) {
                leafDisplay.remove();
                continue;
            }
            if (!acceptsPersistedLeafDisplay(
                    persistedDisplay, leaf, leafDisplay)) {
                leafDisplay.remove();
                continue;
            }
            if (leaf.handle() != null) {
                leafDisplay.remove();
                continue;
            }
            leaf.setHead(reconstructedLeafHead(
                persistedDisplay, leafDisplay, leaf, sculptBlock));
            final var handle = new dev.twme.sculpt.transport.bukkit.BukkitDisplayHandle(leafDisplay);
            handle.setPDC(pathKey, path);
            handle.setPDC(key("sculpt", "type"), "leaf");
            sculptBlock.session.track(handle);
            leaf.attachHandle(handle);
        }

        // Fallback: match unmatched passengers (no sculpt:path from old versions)
        // in order to the first available leaf without a handle.
        if (!unmatched.isEmpty()) {
            final java.util.List<OctreeNode> leaves = sculptBlock.root.collectLeaves();
            int leafIdx = 0;
            for (final Entity passenger : unmatched) {
                if (!(passenger instanceof ItemDisplay leafDisplay)) continue;
                if (persistedDisplay == SculptDisplayMode.AUTO
                        && leafDisplay.getItemStack().getType()
                            != Material.PLAYER_HEAD) {
                    leafDisplay.remove();
                    continue;
                }
                while (leafIdx < leaves.size()
                        && (leaves.get(leafIdx).handle() != null
                            || leaves.get(leafIdx).isRemoved()
                            || (persistedDisplay == SculptDisplayMode.TEXT_DISPLAY
                                && leaves.get(leafIdx).playerHeadTexture() == null))) {
                    leafIdx++;
                }
                if (leafIdx < leaves.size()) {
                    final OctreeNode recoveredLeaf = leaves.get(leafIdx);
                    recoveredLeaf.setHead(reconstructedLeafHead(
                        persistedDisplay, leafDisplay,
                        recoveredLeaf, sculptBlock));
                    final var handle = new dev.twme.sculpt.transport.bukkit.BukkitDisplayHandle(leafDisplay);
                    handle.setPDC(pathKey, recoveredLeaf.pathAsString());
                    handle.setPDC(key("sculpt", "type"), "leaf");
                    sculptBlock.session.track(handle);
                    recoveredLeaf.attachHandle(handle);
                    leafIdx++;
                } else {
                    leafDisplay.remove();
                }
            }
        }

        // Pixel displays are derived cache entries. Never trust or persist a
        // possibly partial passenger snapshot; reconstruct them from the
        // authoritative octree after registration.
        for (final TextDisplay textPixel : textPixels) textPixel.remove();

        registerReconstructedSculptBlock(key, sculptBlock);
        sculptBlock.repairDisplayEntities();
        return true;
    }

    private ChunkHead reconstructedLeafHead(
            final SculptDisplayMode displayMode,
            final ItemDisplay display,
            final OctreeNode leaf,
            final SculptBlock block) {
        if (displayMode == SculptDisplayMode.AUTO) {
            return new ChunkHead(
                display.getItemStack().clone(), display.getTransformation());
        }
        return headResolver.headFor(leaf, block);
    }

    private static boolean acceptsPersistedLeafDisplay(
            final SculptDisplayMode displayMode,
            final OctreeNode leaf,
            final ItemDisplay display) {
        if (displayMode == SculptDisplayMode.TEXT_DISPLAY) {
            return leaf.playerHeadTexture() != null;
        }
        if (displayMode == SculptDisplayMode.AUTO) {
            return display.getItemStack().getType() == Material.PLAYER_HEAD;
        }
        return true;
    }

    // ========================================================================
    //  Utility
    // ========================================================================

    /**
     * Walk a dot-separated path (e.g. "1.3.5") from root, subdividing
     * intermediate nodes on the fly so every segment resolves.
     * Used during reconstruction when subdividedRaw PDC is stale.
     */
    private static OctreeNode ensureNodePath(OctreeNode root, String path) {
        if (path == null || path.isEmpty()) return root;
        OctreeNode node = root;
        for (final String part : path.split("\\.")) {
            final int idx;
            try {
                idx = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
            if (idx < 0 || idx > 7) return null;
            if (node.isLeaf()) node.subdivide();
            node = node.children()[idx];
        }
        return node;
    }

    private static Quaternionf parseQuaternion(final String str) {
        if (str == null || str.isEmpty()) return new Quaternionf();
        final String[] parts = str.split(",");
        if (parts.length != 4) return new Quaternionf();
        try {
            return new Quaternionf(
                    Float.parseFloat(parts[0]),
                    Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2]),
                    Float.parseFloat(parts[3]));
        } catch (final NumberFormatException e) {
            return new Quaternionf();
        }
    }

    public static NamespacedKey key(final String ns, final String key) {
        return new NamespacedKey(ns, key);
    }

    // ========================================================================
    //  SculptBlockRegistry for SculptEditListener
    // ========================================================================

    private final class SculptBlockRegistryImpl
            implements SculptEditListener.SculptBlockRegistry {

        @Override
        public SculptBlock getActiveBlock(final BlockPosKey key) {
            return activeBlocks.get(key);
        }

        @Override
        public boolean registerSculptBlock(final BlockPosKey key, final SculptBlock block) {
            return Sculpt.this.registerSculptBlock(key, block);
        }

        @Override
        public boolean replaceSculptBlock(final BlockPosKey key, final SculptBlock expected,
                                          final SculptBlock replacement) {
            return Sculpt.this.replaceSculptBlock(key, expected, replacement);
        }

        @Override
        public void unregisterSculptBlock(final BlockPosKey key) {
            Sculpt.this.unregisterSculptBlock(key);
        }

        @Override
        public void unregisterSculptBlock(final BlockPosKey key, final SculptBlock block) {
            Sculpt.this.unregisterSculptBlock(key, block);
        }

        @Override
        public int getPlayerGrid(final org.bukkit.entity.Player player) {
            return gridSizeFor(player);
        }

        @Override
        public boolean isSculptMode(final org.bukkit.entity.Player player) {
            return Sculpt.this.isSculptMode(player);
        }

        @Override
        public boolean isSculptModeActive(final org.bukkit.entity.Player player) {
            return Sculpt.this.isSculptModeActive(player);
        }

        @Override
        public BlockData heldBlockData(final org.bukkit.entity.Player player) {
            return Sculpt.this.heldBlockData(player);
        }

        @Override
        public CellMaterial heldCellMaterial(final org.bukkit.entity.Player player) {
            return Sculpt.this.heldCellMaterial(player);
        }

        @Override
        public FillMode fillModeFor(final org.bukkit.entity.Player player) {
            return Sculpt.this.fillModeFor(player);
        }

        @Override
        public SculptDisplayMode displayModeFor(
                final org.bukkit.entity.Player player) {
            return Sculpt.this.displayModeFor(player);
        }

        @Override
        public TextBlockRenderer textBlockRenderer() {
            return Sculpt.this.getTextBlockRenderer();
        }

        @Override
        public boolean isNonBakeable(final org.bukkit.Material material) {
            return nonBakeableBlocks != null && nonBakeableBlocks.isNonBakeable(material);
        }

        @Override
        public boolean isMaterialSupported(
                final org.bukkit.Material material,
                final SculptDisplayMode displayMode) {
            return Sculpt.this.isMaterialSupported(material, displayMode);
        }

        @Override
        public boolean isHoverEnabled(final org.bukkit.entity.Player player) {
            return Sculpt.this.isHoverEnabled(player);
        }
    }

}
