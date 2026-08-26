package dev.twme.sculpt.assets.model;

import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.joml.Quaternionf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.FaceDir;

/**
 * Resolves a {@link BlockKey} to a {@link BlockModel} by walking the vanilla
 * blockstate → model parent chain on mcasset.cloud and downloading the
 * referenced textures.
 *
 * <p>A model is accepted iff its selected material source has a full-cube
 * element ({@code from=[0,0,0]} to {@code [16,16,16]}) with all 6 face
 * textures resolvable. Slabs select their vanilla {@code type=double}
 * blockstate model, which represents two matching slabs stacked into a full
 * cube and preserves slab-specific textures such as the smooth-stone seam.
 * Other property variants use the canonical (x=0, y=0) model, falling back to
 * the first one. <em>All</em> variants are still recorded in
 * {@link BlockModel#variantRotations()} for runtime orientation matching.
 *
 * <p>Tint detection is driven by {@code tintindex} in the model JSON (not a
 * hardcoded list), so any block whose model faces carry {@code tintindex}
 * automatically surfaces as {@link BlockModel#tinted()} without needing
 * further configuration.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.assets.model.ModelResolver}).
 * Key porting notes:
 * <ul>
 *   <li>{@code GSON} is now shaded under {@code dev.twme.sculpt.shaded.gson}
 *       (see {@code DEVELOPMENT_PLAN.md} §8) — the import is a shaded import.</li>
 *   <li>{@link #parseVariants} is public so {@code assemble.SculptFactory}
 *       can re-parse the variant map at spawn time without re-fetching the
 *       blockstate JSON (Tessera had it private; we promote it for that use).</li>
 *   <li>The {@code gAMA/sRGB/iCCP/cHRM} PNG chunk stripping and grayscale
 *       {@code TYPE_INT_ARGB} normalisation are <em>load-bearing</em> —
 *       see {@link #stripColorChunks} and {@link #normalizeToArgb} for the
 *       detailed reasoning. Removing either will make every grayscale
 *       texture (e.g. {@code stone.png}) bake ~46% too bright.</li>
 * </ul>
 */
public final class ModelResolver {

    /**
     * Model parents that {@link #resolveModelChain} recognises as a
     * "terminal" cube shape. Used purely for the {@link BlockModel#parentChain()}
     * display field; cube-ness is actually checked downstream by looking for
     * a full-cube element (Tessera used to gate on parent name and missed
     * {@code block/leaves} / {@code block/grass_block} which define elements
     * under {@code block/block} rather than via a named cube parent).
     */
    private static final Set<String> CUBE_PARENTS = Set.of(
            "minecraft:block/cube",
            "minecraft:block/cube_all",
            "minecraft:block/cube_column",
            "minecraft:block/cube_column_horizontal",
            "minecraft:block/cube_bottom_top",
            "minecraft:block/cube_top",
            "minecraft:block/cube_mirrored",
            "minecraft:block/cube_mirrored_all",
            "minecraft:block/cube_directional",
            "minecraft:block/orientable",
            "minecraft:block/orientable_with_bottom");

    private final McAssetClient client;
    private final Logger logger;
    private final String version;
    private final boolean allowTransparentTextures;
    private final Map<BlockKey, Optional<BlockModel>> cache =
            new ConcurrentHashMap<>();

    public ModelResolver(McAssetClient client, Logger logger, String version) {
        this(client, logger, version, false);
    }

    /**
     * @param allowTransparentTextures whether full-cube models with alpha may
     *        be returned (TextDisplay rendering supports them; heads do not)
     */
    public ModelResolver(
            McAssetClient client,
            Logger logger,
            String version,
            boolean allowTransparentTextures) {
        this.client = client;
        this.logger = logger;
        this.version = version;
        this.allowTransparentTextures = allowTransparentTextures;
    }

    public Optional<BlockModel> resolve(BlockKey key) {
        return cache.computeIfAbsent(key, this::doResolve);
    }

    private Optional<BlockModel> doResolve(BlockKey key) {
        try {
            JsonObject blockstate = JsonParser.parseString(
                    client.fetchString(version, "blockstates/" + key.path() + ".json"))
                    .getAsJsonObject();
            BlockstateVariants vs = parseVariants(blockstate);
            if (vs.canonicalModelName() == null) {
                logger.fine("[" + key + "] blockstate has no usable variant; skipping");
                return Optional.empty();
            }

            final String materialModel = preferredMaterialModel(
                key, blockstate, vs.canonicalModelName());
            ResolvedModel resolved = resolveModelChain(materialModel);

            FaceData fd = pickFaceTextures(key, resolved);
            if (fd.bases.size() != 6) {
                logger.fine("[" + key + "] failed to resolve all 6 faces, got " + fd.bases.keySet());
                return Optional.empty();
            }
            // Player-head skins don't support partial transparency — a
            // transparent face texture (leaves, glass, ice, stained glass)
            // would render as a hole in the ItemDisplay entity. Reject any
            // block whose base textures have at least one non-opaque pixel.
            // Overlay textures (e.g. grass_block side overlay) are composited
            // onto their opaque base faces, so the final baked pixel is always
            // fully opaque; overlays are not checked here.
            if (!allowTransparentTextures && hasTransparentPixels(fd.bases)) {
                logger.fine("[" + key + "] skipping — base textures contain transparent pixels");
                return Optional.empty();
            }
            return Optional.of(new BlockModel(key, fd.bases, fd.tinted, fd.overlays,
                    resolved.terminalParent, vs.rotations()));
        } catch (McAssetClient.AssetNotFoundException missing) {
            logger.fine("[" + key + "] no blockstate on mcasset.cloud (" + missing.getMessage() + ")");
            return Optional.empty();
        } catch (IOException io) {
            logger.log(Level.WARNING, "[" + key + "] I/O error resolving model", io);
            return Optional.empty();
        } catch (RuntimeException re) {
            logger.log(Level.WARNING, "[" + key + "] unexpected error resolving model", re);
            return Optional.empty();
        }
    }

    /**
     * Per-variant rotation hints from a blockstate JSON. Carries the raw
     * {@code x}/{@code y} integers (multiples of 90, in degrees) so the
     * values round-trip cleanly through the registry without any
     * quaternion-to-Euler ambiguity. Use {@link #toQuat} for the runtime
     * world-space rotation.
     *
     * <p>Vanilla composition order: {@code y} (yaw) is applied after
     * {@code x} (pitch) — i.e. tilt the cube up/down first, then spin around
     * the world Y axis.
     *
     * <p><b>Y sign:</b> vanilla blockstate's {@code y} rotates clockwise as
     * viewed from above, so {@code y=90} maps the model's {@code -Z} face to
     * world {@code +X} (north→east, matching what {@code facing=east} variants
     * expect). JOML's {@code rotateY} is the standard right-hand rule
     * (counter-clockwise from above), so we negate the angle here. {@code x}
     * matches in both — vanilla's pitch and JOML's {@code rotateX} are both
     * right-handed, verified against {@code oak_log[axis=z]} which uses
     * {@code x=90} alone.
     */
    public record VariantRotation(int xDeg, int yDeg) {
        public Quaternionf toQuat() {
            return new Quaternionf()
                    .rotateY((float) Math.toRadians(-yDeg))
                    .rotateX((float) Math.toRadians(xDeg));
        }
    }

    /**
     * Carries the per-block result of parsing a blockstate JSON: which model
     * to bake textures from (canonical = the variant with no x/y rotation
     * hints, falling back to the first one), plus a map from variant key
     * (e.g. {@code "axis=x"}, {@code "facing=west,lit=false"}) to the
     * rotation that variant requires for correct world orientation.
     *
     * <p>Used by {@link #doResolve} to feed both the texture pipeline and
     * the runtime spawn-time rotation lookup. Variant keys mirror vanilla's
     * format (alphabetically sorted, comma-separated). Multipart blockstates
     * collapse to empty since per-state rotation isn't meaningful for them
     * in v1.
     */
    public record BlockstateVariants(String canonicalModelName, Map<String, VariantRotation> rotations) {}

    /**
     * Public so {@code assemble.SculptFactory} can re-parse a blockstate JSON
     * at spawn time to populate the runtime variant table without re-fetching
     * the JSON (it already has the blockstate text from the bake step).
     */
    public static BlockstateVariants parseVariants(JsonObject blockstate) {
        if (blockstate.has("variants")) {
            JsonObject variants = blockstate.getAsJsonObject("variants");
            String canonical = null;
            String firstSeen = null;
            Map<String, VariantRotation> rotations = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : variants.entrySet()) {
                JsonElement v = e.getValue();
                JsonObject obj = v.isJsonArray() ? v.getAsJsonArray().get(0).getAsJsonObject() : v.getAsJsonObject();
                String model = obj.get("model").getAsString();
                int xDeg = obj.has("x") ? obj.get("x").getAsInt() : 0;
                int yDeg = obj.has("y") ? obj.get("y").getAsInt() : 0;
                rotations.put(e.getKey(), new VariantRotation(xDeg, yDeg));
                if (firstSeen == null) firstSeen = model;
                if (canonical == null && xDeg == 0 && yDeg == 0) canonical = model;
            }
            return new BlockstateVariants(canonical != null ? canonical : firstSeen, rotations);
        }
        if (blockstate.has("multipart")) {
            JsonArray arr = blockstate.getAsJsonArray("multipart");
            if (!arr.isEmpty()) {
                JsonElement applied = arr.get(0).getAsJsonObject().get("apply");
                JsonObject obj = applied.isJsonArray() ? applied.getAsJsonArray().get(0).getAsJsonObject() : applied.getAsJsonObject();
                return new BlockstateVariants(obj.get("model").getAsString(), Collections.emptyMap());
            }
        }
        return new BlockstateVariants(null, Collections.emptyMap());
    }

    /**
     * Slab cells are rendered as cubes, so use the model Minecraft itself uses
     * when two slabs occupy one block. This is more accurate than deriving a
     * full-block material name: some slabs have a dedicated double model.
     */
    static String preferredMaterialModel(
            final BlockKey key,
            final JsonObject blockstate,
            final String fallback) {
        if (key == null || !key.path().endsWith("_slab")
                || !blockstate.has("variants")) return fallback;

        for (final Map.Entry<String, JsonElement> entry
                : blockstate.getAsJsonObject("variants").entrySet()) {
            if (!hasVariantProperty(entry.getKey(), "type", "double")) continue;
            final JsonElement value = entry.getValue();
            final JsonObject variant = value.isJsonArray()
                ? value.getAsJsonArray().get(0).getAsJsonObject()
                : value.getAsJsonObject();
            final JsonElement model = variant.get("model");
            if (model != null && model.isJsonPrimitive()
                    && model.getAsJsonPrimitive().isString()) {
                return model.getAsString();
            }
        }
        return fallback;
    }

    private static boolean hasVariantProperty(
            final String variantKey,
            final String property,
            final String expectedValue) {
        for (final String assignment : variantKey.split(",")) {
            final int separator = assignment.indexOf('=');
            if (separator < 1) continue;
            if (property.equals(assignment.substring(0, separator).trim())
                    && expectedValue.equals(
                        assignment.substring(separator + 1).trim())) return true;
        }
        return false;
    }

    private static final class ResolvedModel {
        final String terminalParent;
        final Map<String, String> textureVars;
        final JsonArray elements;
        ResolvedModel(String terminalParent, Map<String, String> textureVars, JsonArray elements) {
            this.terminalParent = terminalParent;
            this.textureVars = textureVars;
            this.elements = elements;
        }
    }

    /**
     * Walk the parent chain to the root, accumulating texture-variable bindings
     * (child-first wins via {@code putIfAbsent}) and capturing the first
     * {@code elements} array seen (vanilla overrides elements wholesale, so
     * first-seen wins). The terminal parent is the first
     * {@link #CUBE_PARENTS} entry encountered.
     *
     * <p>We always walk to the root rather than breaking at the first cube
     * parent: vanilla's {@code elements} live in {@code block/cube} (the
     * deepest parent), but most blocks' immediate parent is something like
     * {@code cube_column} or {@code orientable} (also in {@link #CUBE_PARENTS}).
     * Stopping early would never see the elements.
     */
    private ResolvedModel resolveModelChain(String firstModel) throws IOException {
        Map<String, String> tex = new LinkedHashMap<>();
        String current = withDefaultNamespace(firstModel);
        String terminal = null;
        JsonArray elements = null;
        int hops = 0;

        while (hops++ < 16) {
            String path = stripNamespace(current);
            JsonObject obj = JsonParser.parseString(
                    client.fetchString(version, "models/" + path + ".json"))
                    .getAsJsonObject();

            if (obj.has("textures")) {
                for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("textures").entrySet()) {
                    // Don't overwrite — child models override parents, and
                    // we walk child-first. Minecraft 26.2 also permits an
                    // object texture slot such as
                    // {"sprite":"minecraft:block/orange_stained_glass",
                    //  "force_translucent":true}; the sprite is the same
                    // texture reference used by the legacy string form.
                    final String texture = textureSlot(e.getValue());
                    if (texture != null) tex.putIfAbsent(e.getKey(), texture);
                }
            }

            if (elements == null && obj.has("elements")) {
                elements = obj.getAsJsonArray("elements");
            }

            if (terminal == null && CUBE_PARENTS.contains(current)) {
                terminal = current;
            }

            if (!obj.has("parent")) {
                if (terminal == null) terminal = current;
                break;
            }
            current = withDefaultNamespace(obj.get("parent").getAsString());
        }

        // Resolve indirect references (e.g. "down": "#all") against the bindings.
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : tex.entrySet()) {
            resolved.put(e.getKey(), resolveTextureRef(e.getValue(), tex));
        }
        return new ResolvedModel(terminal, resolved, elements);
    }

    /** Resolve both legacy string slots and Minecraft 26.2 sprite objects. */
    static String textureSlot(final JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        if (!element.isJsonObject()) return null;
        final JsonElement sprite = element.getAsJsonObject().get("sprite");
        if (sprite == null || !sprite.isJsonPrimitive()
                || !sprite.getAsJsonPrimitive().isString()) return null;
        return sprite.getAsString();
    }

    private static String resolveTextureRef(String value, Map<String, String> bindings) {
        String s = value;
        int hops = 0;
        while (s.startsWith("#") && hops++ < 8) {
            String next = bindings.get(s.substring(1));
            if (next == null) break;
            s = next;
        }
        return s;
    }

    private record FaceData(
            EnumMap<FaceDir, BufferedImage> bases,
            EnumSet<FaceDir> tinted,
            EnumMap<FaceDir, BufferedImage> overlays) {}

    /**
     * Extract per-face base textures, tintindex flags, and overlay textures
     * by reading all full-cube {@code elements} entries.
     *
     * <p>Pass 1 — first full-cube element: loads the 6 base textures and
     * records which faces carry {@code "tintindex": 0} (i.e. should be
     * multiplied by the biome color at bake time).
     *
     * <p>Pass 2 — any additional full-cube elements: treated as
     * alpha-composited overlays. Vanilla grass_block uses this for its
     * tinted side overlay ({@code grass_block_side_overlay.png}). Each
     * face of the overlay element that has a {@code tintindex} is collected;
     * at bake time it is tinted then composited on top of the base face.
     */
    private FaceData pickFaceTextures(BlockKey key, ResolvedModel m) throws IOException {
        EnumMap<FaceDir, BufferedImage> bases = new EnumMap<>(FaceDir.class);
        if (m.elements == null) {
            logger.fine("[" + key + "] no elements in model chain; skipping");
            return new FaceData(bases, EnumSet.noneOf(FaceDir.class), new EnumMap<>(FaceDir.class));
        }

        boolean foundBase = false;
        EnumSet<FaceDir> tinted = EnumSet.noneOf(FaceDir.class);
        EnumMap<FaceDir, BufferedImage> overlays = new EnumMap<>(FaceDir.class);

        for (JsonElement el : m.elements) {
            JsonObject elem = el.getAsJsonObject();
            if (!isFullCubeBounds(elem) || !elem.has("faces")) continue;
            JsonObject facesJson = elem.getAsJsonObject("faces");

            if (!foundBase) {
                // First full-cube element — extract the 6 base textures.
                for (FaceDir d : FaceDir.values()) {
                    String jsonName = d.jsonName();
                    if (!facesJson.has(jsonName)) {
                        logger.fine("[" + key + "] face " + jsonName + " missing from cube element");
                        return new FaceData(new EnumMap<>(FaceDir.class),
                                EnumSet.noneOf(FaceDir.class), new EnumMap<>(FaceDir.class));
                    }
                    JsonObject faceObj = facesJson.getAsJsonObject(jsonName);
                    String ref = faceObj.get("texture").getAsString();
                    bases.put(d, loadTexture(resolveTextureRef(ref, m.textureVars)));
                    if (faceObj.has("tintindex") && faceObj.get("tintindex").getAsInt() >= 0) {
                        tinted.add(d);
                    }
                }
                foundBase = true;
            } else {
                // Subsequent full-cube elements — treat as tinted overlays.
                for (FaceDir d : FaceDir.values()) {
                    String jsonName = d.jsonName();
                    if (!facesJson.has(jsonName)) continue;
                    JsonObject faceObj = facesJson.getAsJsonObject(jsonName);
                    if (!faceObj.has("tintindex") || faceObj.get("tintindex").getAsInt() < 0) continue;
                    String ref = faceObj.get("texture").getAsString();
                    overlays.put(d, loadTexture(resolveTextureRef(ref, m.textureVars)));
                }
            }
        }

        if (!foundBase) {
            logger.fine("[" + key + "] no full-cube element with faces; skipping");
        }
        return new FaceData(bases, tinted, overlays);
    }

    private static boolean hasTransparentPixels(EnumMap<FaceDir, BufferedImage> faces) {
        for (BufferedImage img : faces.values()) {
            int w = img.getWidth();
            int h = img.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (((img.getRGB(x, y) >>> 24) & 0xFF) < 255) return true;
                }
            }
        }
        return false;
    }

    private static boolean isFullCubeBounds(JsonObject element) {
        if (!element.has("from") || !element.has("to")) return false;
        JsonArray from = element.getAsJsonArray("from");
        JsonArray to = element.getAsJsonArray("to");
        return from.get(0).getAsFloat() == 0f && from.get(1).getAsFloat() == 0f && from.get(2).getAsFloat() == 0f
            && to.get(0).getAsFloat() == 16f && to.get(1).getAsFloat() == 16f && to.get(2).getAsFloat() == 16f;
    }

    private BufferedImage loadTexture(String ref) throws IOException {
        if (ref == null) throw new IOException("missing texture variable");
        String path = stripNamespace(ref);
        // Block textures live under textures/<path>.png with leading "block/"
        // already part of the path (e.g. "block/stone").
        byte[] png = client.fetch(version, "textures/" + path + ".png");
        // ImageIO.read returns null for unrecognised formats; null-check before
        // normalizeToArgb dereferences it.
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stripColorChunks(png)));
        if (decoded == null) throw new IOException("failed to decode " + ref);
        BufferedImage full = normalizeToArgb(decoded);
        // Animated textures are tall (e.g. 16×64 for 4 frames). Take frame 0.
        if (full.getHeight() > full.getWidth()) {
            return full.getSubimage(0, 0, full.getWidth(), full.getWidth());
        }
        return full;
    }

    /**
     * Strips {@code gAMA}, {@code sRGB}, {@code iCCP}, and {@code cHRM} chunks
     * from a PNG byte array before passing it to {@link ImageIO#read}.
     *
     * <p>Java's ImageIO honours these chunks and applies gamma / colour-profile
     * corrections when decoding. Vanilla Minecraft block textures (e.g.
     * {@code stone.png}) ship with a {@code gAMA=100000} chunk that marks the
     * data as linear-light; ImageIO responds by encoding it to sRGB display
     * space, boosting mid-range grey 126 → ~182 in the resulting
     * {@link BufferedImage}. That inflated value is then baked into our player-
     * head skin and rendered ~46 % too bright in-game. Stripping the metadata
     * tells ImageIO to treat the bytes as plain sRGB (the same assumption the
     * Minecraft block-texture atlas loader uses), preserving exact pixel values.
     */
    static byte[] stripColorChunks(byte[] png) {
        if (png.length < 8) return png;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(png.length);
            out.write(png, 0, 8); // PNG signature
            int pos = 8;
            while (pos + 12 <= png.length) {
                int len = ((png[pos]   & 0xFF) << 24) | ((png[pos+1] & 0xFF) << 16)
                        | ((png[pos+2] & 0xFF) <<  8) |  (png[pos+3] & 0xFF);
                if (len < 0 || pos + 12 + len > png.length) break; // malformed
                String type = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
                boolean strip = type.equals("gAMA") || type.equals("sRGB")
                             || type.equals("iCCP") || type.equals("cHRM");
                if (!strip) out.write(png, pos, 12 + len);
                pos += 12 + len;
            }
            return out.toByteArray();
        } catch (Exception e) {
            return png; // ByteArrayOutputStream never throws; satisfy javac
        }
    }

    /**
     * Ensures the decoded image is {@link BufferedImage#TYPE_INT_ARGB} with raw
     * pixel values unchanged. This is necessary for grayscale PNGs: Java's
     * {@code TYPE_BYTE_GRAY} uses {@code CS_GRAY} (linear, gamma=1.0). Any call
     * to {@link BufferedImage#getRGB} on such an image converts linear-gray →
     * sRGB by applying the gamma-2.2 encode, brightening mid-range values
     * (e.g. stone gray 126 → ~182). Vanilla stone.png is a grayscale PNG, which
     * is why the baked skin appeared ~46% too bright while RGBA textures like
     * light_gray_concrete were unaffected.
     *
     * <p>For grayscale images the raster is read directly — {@code getSample()}
     * returns the raw stored byte without colour-space conversion — and the grey
     * value is mapped to R=G=B in the ARGB output. For all other types the image
     * is already in an sRGB-compatible space and a plain {@link Graphics2D} copy
     * (no colour conversion) suffices.
     */
    static BufferedImage normalizeToArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        if (src.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY) {
            Raster raster = src.getRaster();
            boolean hasAlpha = src.getColorModel().hasAlpha();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = raster.getSample(x, y, 0);
                    int a = hasAlpha ? raster.getSample(x, y, 1) : 255;
                    dst.setRGB(x, y, (a << 24) | (v << 16) | (v << 8) | v);
                }
            }
        } else {
            Graphics2D g = dst.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
        }
        return dst;
    }

    private static String withDefaultNamespace(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        return low.contains(":") ? low : "minecraft:" + low;
    }

    private static String stripNamespace(String s) {
        int i = s.indexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }
}
