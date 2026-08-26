package dev.twme.sculpt.nms;

import dev.twme.sculpt.assets.fetch.McAssetClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the resolved client-side biome tint for a block via NMS, accessed
 * reflectively so the plugin can be compiled against the public Bukkit/Paper
 * API alone (the {@code io.papermc.paper:paper-api} artifact has no NMS
 * surface, and Paper does not publish the full server jar to Maven).
 *
 * <p>Mirrors the value vanilla Minecraft would multiply into the texture
 * when rendering — we apply the same multiplier to the source PNGs at bake
 * time so the SculptBlock matches its real-block neighbours.
 *
 * <p><b>This is the only NMS-touching class in the plugin</b>
 * ({@code DEVELOPMENT_PLAN.md} §2 D-1 carve-out, §3 package). Everything
 * else stays on the Bukkit / Paper API surface. Reflection rather than
 * direct {@code net.minecraft.*} import also makes Paper version bumps
 * fail-soft: a missing class or method at runtime logs once and returns 0,
 * not a hard {@code NoClassDefFoundError} at class-load.
 *
 * <p><b>Server-side colormaps:</b> vanilla's {@code GrassColor.pixels} and
 * {@code FoliageColor.pixels} arrays are populated only by the client's
 * resource loader. On a dedicated server they're left as the default
 * all-zero array, so {@code Biome.getGrassColor(...)} returns 0 for every
 * biome that doesn't carry an explicit override. {@link #prepareColormaps}
 * fetches the vanilla colormap PNGs through {@link McAssetClient} and
 * calls vanilla's {@code GrassColor.init(int[])} / {@code FoliageColor.init(int[])}
 * setters reflectively. Call once during plugin enable, before any
 * block-break events register.
 *
 * <p><b>Main thread only.</b> Biome lookup reads chunk biome storage, which
 * races with chunk unloads off-thread. Callers must invoke {@link #read}
 * from the main server thread, capture the returned int, and only then hop
 * off to async work.
 *
 * <p>Returns {@code 0} (untinted sentinel) for: any block type we don't
 * have a tint function for, and any reflection / NMS linkage error.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.nms.BlockTintReader})
 * with the {@code net.minecraft.*} imports replaced by reflective lookups.
 */
public final class BlockTintReader {

    private BlockTintReader() {}

    /** Block types whose vanilla renderer applies the grass biome tint. */
    private static final Set<String> GRASS_TINTED_BLOCKS = Set.of(
            "grass_block",
            "short_grass", "tall_grass",
            "fern",        "large_fern",
            "sugar_cane",  "potted_fern");

    private static volatile boolean colormapsLoaded;
    private static final AtomicBoolean tintReadErrorLogged = new AtomicBoolean();
    // Stashed by prepareColormaps so the static read() error path can log
    // through the plugin logger instead of resolving "Sculpt" by name.
    private static volatile Logger logger;

    // ---- Resolved NMS reflection handles (populated on first use) ------------

    private static Method   grassColorInit;    // static void init(int[])
    private static Method   foliageColorInit;  // static void init(int[])
    private static Method   worldGetHandle;    // CraftWorld.getHandle(): Object (LevelReader)
    private static Method   levelGetBiome;     // LevelReader.getBiome(BlockPos): Holder<Biome>
    private static Method   holderValue;       // Holder.value(): Biome
    private static Method   biomeGetGrassColor;// Biome.getGrassColor(double, double): int
    private static Method   biomeGetFoliageColor;// Biome.getFoliageColor(): int
    private static Constructor<?> blockPosCtor;// BlockPos(int, int, int)
    private static boolean nmsResolved;

    /**
     * Fetch the vanilla grass + foliage colormap PNGs (256×256 each) and
     * inject them into vanilla's static pixel arrays so server-side
     * {@code Biome.getGrassColor(...)} / {@code getFoliageColor()} return
     * the correct per-biome tint.
     *
     * <p>Idempotent — subsequent calls are no-ops. Failures are logged but
     * not thrown; tinted blocks then keep returning 0 from {@link #read}
     * and skip baking, leaving vanilla particles as the user-visible
     * fallback.
     */
    public static synchronized void prepareColormaps(McAssetClient assets, String mcVersion, Logger log) {
        logger = log;
        if (colormapsLoaded) return;
        try {
            resolveNms();
            if (grassColorInit == null || foliageColorInit == null) {
                log.warning("[Sculpt] Could not resolve GrassColor / FoliageColor NMS classes; "
                        + "tinted blocks will fall back to vanilla particles");
                return;
            }
            int[] grass   = loadColormap(assets.fetch(mcVersion, "textures/colormap/grass.png"));
            int[] foliage = loadColormap(assets.fetch(mcVersion, "textures/colormap/foliage.png"));
            grassColorInit.invoke(null, (Object) grass);
            foliageColorInit.invoke(null, (Object) foliage);
            colormapsLoaded = true;
            log.info("[Sculpt] Biome colormaps loaded (grass + foliage)");
        } catch (IOException | LinkageError | RuntimeException | IllegalAccessException
                 | InvocationTargetException e) {
            log.log(Level.WARNING,
                    "[Sculpt] Could not load biome colormaps; tinted blocks will fall back to vanilla particles", e);
        }
    }

    /**
     * Read the biome tint for the block currently present at this position.
     */
    public static int read(org.bukkit.block.Block block) {
        return readAt(block, block.getType());
    }

    /**
     * Read the biome tint at a position for the material that will be rendered.
     * The rendered material may differ from the position's current backing block,
     * for example when placing a grass SculptBlock into an AIR position.
     *
     * <p>Returns 0 for materials without a tint function, biomes with no
     * available tint, or any reflection / NMS linkage error (logged once).
     * The returned value has alpha forced to {@code 0xFF} for a stable
     * {@code BakeKey} identity.
     */
    public static int readAt(
            org.bukkit.block.Block position,
            org.bukkit.Material renderedMaterial) {
        final String materialName = renderedMaterial.getKey().getKey();
        final boolean foliage = materialName.endsWith("_leaves");
        if (!foliage && !GRASS_TINTED_BLOCKS.contains(materialName)) return 0;
        if (materialName.equals("spruce_leaves")) return 0xFF619961;
        if (materialName.equals("birch_leaves")) return 0xFF80A755;
        try {
            if (!nmsResolved) resolveNms();
            if (foliage && biomeGetFoliageColor == null) return 0;
            if (!foliage && biomeGetGrassColor == null) return 0;
            Object worldHandle = worldGetHandle.invoke(position.getWorld());
            Object pos = blockPosCtor.newInstance(
                    position.getX(), position.getY(), position.getZ());
            Object biomeHolder = levelGetBiome.invoke(worldHandle, pos);
            Object biome = holderValue.invoke(biomeHolder);
            final int rgb;
            if (foliage) {
                rgb = (int) biomeGetFoliageColor.invoke(biome);
            } else {
                rgb = (int) biomeGetGrassColor.invoke(
                    biome, (double) position.getX(), (double) position.getZ());
            }
            // Treat 0 RGB as "no tint available" (e.g. colormap not loaded
            // and biome has no explicit override) and return the untinted
            // sentinel so we don't bake a useless all-black variant.
            if ((rgb & 0xFFFFFF) == 0) return 0;
            // Force opaque alpha for a stable BakeKey identity.
            return 0xFF000000 | (rgb & 0xFFFFFF);
        } catch (LinkageError | RuntimeException | IllegalAccessException | InvocationTargetException
                 | InstantiationException e) {
            if (tintReadErrorLogged.compareAndSet(false, true)) {
                Logger log = logger != null ? logger : Logger.getLogger("Sculpt");
                log.log(Level.WARNING,
                        "[Sculpt] BlockTintReader.read failed (Paper API incompatibility?) — "
                                + "tinted blocks will skip baking", e);
            }
            return 0;
        }
    }

    /**
     * Resolve all the NMS classes and methods we need. Idempotent. Failures
     * are recorded as null Method handles — callers check and bail.
     *
     * <p>Class names are the stable 1.20.5+ Mojang-mapped names; obfuscated
     * (Spigot-style) names are not used because Paper ships Mojang mappings
     * and our only target is Paper 1.21+ (spec §2 D-1).
     */
    private static void resolveNms() {
        if (nmsResolved) return;
        nmsResolved = true; // do not retry on failure
        try {
            Class<?> mcBlockPos = Class.forName("net.minecraft.core.BlockPos");
            blockPosCtor = mcBlockPos.getConstructor(int.class, int.class, int.class);

            Class<?> grassColor   = Class.forName("net.minecraft.world.level.GrassColor");
            Class<?> foliageColor = Class.forName("net.minecraft.world.level.FoliageColor");
            grassColorInit   = grassColor.getMethod("init",   int[].class);
            foliageColorInit = foliageColor.getMethod("init", int[].class);

            // CraftWorld is part of the public CraftBukkit package (org.bukkit.craftbukkit.*)
            // and is shipped with paper-api 1.21+. The Method handles it returns
            // are the Mojang-mapped NMS types.
            Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            worldGetHandle = craftWorld.getMethod("getHandle");

            Class<?> levelReader = Class.forName("net.minecraft.world.level.LevelReader");
            levelGetBiome = levelReader.getMethod("getBiome", mcBlockPos);

            Class<?> holder = Class.forName("net.minecraft.core.Holder");
            holderValue = holder.getMethod("value");

            Class<?> biome = Class.forName("net.minecraft.world.level.biome.Biome");
            biomeGetGrassColor = biome.getMethod("getGrassColor", double.class, double.class);
            biomeGetFoliageColor = biome.getMethod("getFoliageColor");
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Leave handles null; callers will detect and skip. read() logs
            // the first failure so admins can see why tinted blocks bail.
        }
    }

    private static int[] loadColormap(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("failed to decode colormap PNG");
        int w = img.getWidth();
        int h = img.getHeight();
        int[] out = new int[w * h];
        img.getRGB(0, 0, w, h, out, 0, w);
        return out;
    }
}
