package dev.twme.sculpt.skin.bake;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.assets.model.BlockModel;
import dev.twme.sculpt.assets.model.ModelResolver;
import dev.twme.sculpt.assets.model.ModelResolver.VariantRotation;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.ChunkSpec;
import dev.twme.sculpt.skin.HeadSkin;
import dev.twme.sculpt.skin.HeadSkinPacker;
import dev.twme.sculpt.skin.SkinAssembler;
import dev.twme.sculpt.skin.SkinState;
import dev.twme.sculpt.skin.SkinUploader;
import dev.twme.sculpt.skin.store.HeadsStore.StoredBlock;
import dev.twme.sculpt.skin.store.HeadsStore.StoredSkin;
import dev.twme.sculpt.skin.store.SqliteHeadsStore;
import dev.twme.sculpt.split.TextureSplitter;
import dev.twme.sculpt.util.ExceptionSummary;

/**
 * Standalone entry point for the offline bake step. Reads a list of block
 * IDs from {@code bake-blocks.txt}, fetches their assets from
 * mcasset.cloud, splits the textures, packs heads, uploads to MineSkin,
 * and stores completed plugin-generated textures in SQLite.
 *
 * <p>This utility never creates or modifies administrator-supplied SBH
 * catalogs. Its SQLite output uses the same runtime-cache schema as Sculpt.
 *
 * <p>Args:
 * <pre>
 *   --input    bake-blocks.txt (one block ID per line, # comments)
 *   --cache    cache root for assets, skin PNGs, and heads.sqlite
 *   --version  Minecraft version tag (defaults to {@code 1.21.11})
 *   --gridN    chunk grid size (defaults to 4)
 * </pre>
 *
 * <p>Reads {@code MINESKIN_API_KEY} from the environment. Uploads work
 * without it on the free tier but at low throughput; with it, MineSkin
 * lifts the per-IP rate limit.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.skin.bake.BakeMain}).
 */
public final class BakeMain {

    public static void main(String[] argv) throws Exception {
        Map<String, String> args = parseArgs(argv);
        Path inputPath  = Path.of(args.getOrDefault("input",  "bake-blocks.txt"));
        Path cacheRoot  = Path.of(args.getOrDefault("cache",  "build/tessera-cache"));
        String version  = args.getOrDefault("version", "1.21.11");
        int gridN       = Integer.parseInt(args.getOrDefault("gridN", "4"));
        if (gridN < 1 || gridN > 16) {
            throw new IllegalArgumentException(
                    "gridN must be between 1 and 16; got " + gridN);
        }
        Logger logger = Logger.getLogger("sculpt-bake");
        logger.setUseParentHandlers(false);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.INFO);
        logger.addHandler(ch);
        logger.setLevel(Level.INFO);

        if (!Files.isRegularFile(inputPath)) {
            logger.severe("Input file not found: " + inputPath.toAbsolutePath());
            System.exit(1);
        }

        List<BlockKey> blocks = readBlockList(inputPath);
        logger.info("Baking " + blocks.size() + " blocks at version " + version + " (gridN=" + gridN + ")");

        Files.createDirectories(cacheRoot);
        Path pngDir = cacheRoot.resolve("head-pngs");
        Files.createDirectories(pngDir);

        McAssetClient assets = new McAssetClient(cacheRoot.resolve("assets"));
        ModelResolver resolver = new ModelResolver(assets, logger, version);
        TextureSplitter splitter = new TextureSplitter();
        HeadSkinPacker packer = new HeadSkinPacker();
        SkinAssembler assembler = new SkinAssembler();
        String apiKey = System.getenv("MINESKIN_API_KEY");
        String apiUrl = System.getenv("MINESKIN_API_URL");
        boolean uploadEnabled = apiKey != null && !apiKey.isBlank();
        if (!uploadEnabled) {
            logger.warning("MINESKIN_API_KEY is not set — bake will resolve assets and pack heads,"
                    + " but will skip uploads. Set the env var to publish skins.");
        }
        SkinUploader uploader = uploadEnabled
                ? new SkinUploader(logger, "Sculpt-Bake/0.1", apiKey,
                        apiUrl, 6000L)
                : null;

        try (SqliteHeadsStore scratch = new SqliteHeadsStore(
                logger, cacheRoot.resolve("heads.sqlite"), gridN,
                version, "sculpt-bake-cli")) {
            for (BlockKey key : blocks) {
                try {
                    bakeOne(key, gridN, resolver, splitter, packer, assembler,
                            uploader, pngDir, scratch, logger);
                } catch (Exception e) {
                    ExceptionSummary.log(logger, Level.WARNING,
                            "Failed to bake " + key, e);
                }
            }
        }
        logger.info("Done. Runtime texture cache updated at "
                + cacheRoot.resolve("heads.sqlite").toAbsolutePath());
    }

    private static void bakeOne(BlockKey key, int gridN,
                                ModelResolver resolver, TextureSplitter splitter,
                                HeadSkinPacker packer, SkinAssembler assembler,
                                SkinUploader uploader, Path pngDir,
                                SqliteHeadsStore store, Logger logger)
            throws IOException, ExecutionException, InterruptedException, TimeoutException {

        BakeKey bakeKey = BakeKey.untinted(key);

        // Fast path: a fully-cached block (block file present + every
        // referenced skin payload on disk + chunk count matches the
        // grid's expected surface area) needs no work. Skips the
        // resolver/splitter/packer pipeline entirely. Partial bakes
        // (missing chunks from a prior aborted upload) fall through and
        // re-run the full pipeline so the missing chunks get filled in.
        int expectedVisible = gridN * gridN * gridN;
        Optional<StoredBlock> cached = store.readBlock(bakeKey);
        if (cached.isPresent() && cached.get().chunkHashes().size() == expectedVisible) {
            Set<String> uniqueHashes = new HashSet<>(cached.get().chunkHashes().values());
            boolean allPresent = true;
            for (String hash : uniqueHashes) {
                if (!store.skinExists(hash)) { allPresent = false; break; }
            }
            if (allPresent) {
                logger.info("[" + key + "] cached (" + expectedVisible + " chunks, "
                        + uniqueHashes.size() + " unique heads)");
                return;
            }
        }

        Optional<BlockModel> modelOpt = resolver.resolve(key);
        if (modelOpt.isEmpty()) {
            logger.info("[" + key + "] skipped (non-cube or asset missing)");
            return;
        }
        BlockModel model = modelOpt.get();
        if (model.tinted()) {
            logger.info("[" + key + "] skipped (tinted block; not supported in v1)");
            return;
        }

        List<ChunkSpec> chunks = splitter.split(model, gridN);
        HeadSkinPacker.Result packed = packer.pack(chunks);
        logger.info("[" + key + "] " + chunks.size() + " visible chunks → "
                + packed.uniqueHeads().size() + " unique heads");

        // Skip uploads for hashes already in the scratch store — but still
        // need to map chunk → entry for the block file.
        List<HeadSkin> needUpload = new ArrayList<>();
        for (HeadSkin head : packed.uniqueHeads()) {
            Optional<StoredSkin> existing = store.readSkin(head.contentHash());
            if (existing.isPresent()) {
                StoredSkin s = existing.get();
                head.texture(s.value(), s.signature(), s.mineskinUuid());
                head.state(SkinState.COMPLETED);
                continue;
            }
            assembler.assemble(head, pngDir);
            needUpload.add(head);
        }

        if (!needUpload.isEmpty()) {
            if (uploader == null) {
                logger.info("[" + key + "] " + needUpload.size() + " skins packed locally (PNGs in "
                        + pngDir.toAbsolutePath() + "); upload skipped (no API key)");
            } else {
                logger.info("[" + key + "] uploading " + needUpload.size() + " new skins to MineSkin");
                SkinUploader.Run run = uploader.upload(needUpload, pngDir.getParent(), h -> {});
                run.future().get(10, TimeUnit.MINUTES);

                for (HeadSkin head : needUpload) {
                    if (head.state() != SkinState.COMPLETED) {
                        logger.warning("[" + key + "] head " + head.id()
                                + " ended in state " + head.state());
                    }
                }
            }
        }

        // Build chunk → hash map. Only chunks whose head got a MineSkin
        // texture make it into the block file — otherwise the runtime
        // listener would think the block is supported and spawn FakeBlocks
        // with zero entities (visually a no-op).
        TreeMap<ChunkCoord, String> chunkHashes = new TreeMap<>();
        packed.chunkToHead().forEach((chunk, head) -> {
            if (head.state() == SkinState.COMPLETED) {
                chunkHashes.put(chunk.coord(), head.contentHash());
            }
        });
        if (chunkHashes.isEmpty()) {
            logger.info("[" + key + "] no completed skins; not writing a block entry");
            return;
        }

        // Capture per-variant rotation hints alongside the chunk map so the
        // runtime can orient oak_log[axis=x] etc. correctly without re-
        // parsing the blockstate JSON. Identity-rotation variants
        // (canonical orientation) are dropped — the runtime falls back to
        // identity for unknown variant keys.
        TreeMap<String, VariantRotation> variantMap = new TreeMap<>();
        for (Map.Entry<String, VariantRotation> e : model.variantRotations().entrySet()) {
            if (e.getValue().xDeg() != 0 || e.getValue().yDeg() != 0) {
                variantMap.put(e.getKey(), e.getValue());
            }
        }
        List<StoredSkin> completedSkins = packed.uniqueHeads().stream()
                .filter(head -> head.state() == SkinState.COMPLETED)
                .map(head -> new StoredSkin(
                        head.contentHash(), head.textureValue(),
                        head.textureSignature(), head.mineskinUuid()))
                .toList();
        store.writeBatch(new StoredBlock(bakeKey, chunkHashes, variantMap), completedSkins);
    }

    private static List<BlockKey> readBlockList(Path file) throws IOException {
        List<BlockKey> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(BlockKey.of(line.toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    private static Map<String, String> parseArgs(String[] argv) {
        if ((argv.length & 1) != 0) {
            throw new IllegalArgumentException("Arguments must be --name value pairs");
        }
        Map<String, String> m = new LinkedHashMap<>();
        Set<String> supported = Set.of("input", "cache", "version", "gridN");
        for (int i = 0; i < argv.length; i += 2) {
            String k = argv[i].startsWith("--") ? argv[i].substring(2) : argv[i];
            if (!supported.contains(k)) {
                throw new IllegalArgumentException("Unsupported argument: --" + k);
            }
            m.put(k, argv[i + 1]);
        }
        return m;
    }
}
