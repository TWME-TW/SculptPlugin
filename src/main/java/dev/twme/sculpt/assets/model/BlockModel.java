package dev.twme.sculpt.assets.model;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.FaceDir;

/**
 * Result of {@link ModelResolver#resolve} for a full-cube block. Carries:
 *
 * <ul>
 *   <li>{@link #faces} — base texture per face (one per {@link FaceDir}),
 *       at vanilla resolution (16×16 by default).</li>
 *   <li>{@link #tintedFaces} — which base faces carry a {@code tintindex}
 *       in the model JSON and should be multiplied by the biome color. Empty
 *       for blocks with no tinting.</li>
 *   <li>{@link #overlays} — optional second-element overlay textures. Vanilla
 *       {@code grass_block} has a second full-cube element whose four side
 *       faces are a semi-transparent tinted overlay
 *       ({@code grass_block_side_overlay.png}) composited on top of the
 *       untinted dirt-side base. Always tinted.</li>
 *   <li>{@link #variantRotations} — per-blockstate variant key → world-space
 *       quaternion for spawn-time orientation (e.g. {@code axis=y} →
 *       {@code Ry(-90°)}).</li>
 * </ul>
 *
 * <p>Constructed only by {@code ModelResolver}; consumers (the split / skin
 * / assemble layers) should treat instances as immutable.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.assets.model.BlockModel}).
 */
public final class BlockModel {

    private final BlockKey key;
    private final EnumMap<FaceDir, BufferedImage> faces;
    private final EnumSet<FaceDir> tintedFaces;
    private final EnumMap<FaceDir, BufferedImage> overlays;  // always tinted; may be empty
    private final String parentChain;
    private final Map<String, ModelResolver.VariantRotation> variantRotations;
    private final boolean transparent;

    public BlockModel(BlockKey key,
                      Map<FaceDir, BufferedImage> faces,
                      Set<FaceDir> tintedFaces,
                      Map<FaceDir, BufferedImage> overlays,
                      String parentChain,
                      Map<String, ModelResolver.VariantRotation> variantRotations) {
        if (faces.size() != 6) {
            throw new IllegalArgumentException(
                    "BlockModel must have all 6 face textures, got " + faces.keySet());
        }
        this.key = key;
        this.faces = new EnumMap<>(faces);
        this.tintedFaces = tintedFaces.isEmpty()
                ? EnumSet.noneOf(FaceDir.class)
                : EnumSet.copyOf(tintedFaces);
        this.overlays = overlays.isEmpty()
                ? new EnumMap<>(FaceDir.class)
                : new EnumMap<>(overlays);
        this.parentChain = parentChain;
        this.variantRotations = Map.copyOf(variantRotations);
        this.transparent = hasTransparentPixels(this.faces);
    }

    public BlockKey key() { return key; }
    public String parentChain() { return parentChain; }

    /** True when any face or overlay carries a {@code tintindex} — needs a biome color at bake time. */
    public boolean tinted() { return !tintedFaces.isEmpty() || !overlays.isEmpty(); }

    /** True when at least one base-face pixel is not fully opaque. */
    public boolean transparent() { return transparent; }

    public BufferedImage face(FaceDir dir) { return faces.get(dir); }

    public Map<FaceDir, BufferedImage> faces() { return Map.copyOf(faces); }

    public Map<String, ModelResolver.VariantRotation> variantRotations() { return variantRotations; }

    private static boolean hasTransparentPixels(
            final Map<FaceDir, BufferedImage> textures) {
        for (final BufferedImage image : textures.values()) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) != 0xFF) return true;
                }
            }
        }
        return false;
    }

    /**
     * Return a copy of this model with per-face tinting applied:
     * <ul>
     *   <li>Faces in {@link #tintedFaces}: pixel-multiplied by {@code tintArgb}.</li>
     *   <li>Faces with an overlay: overlay is tinted then alpha-composited on
     *       top of the (possibly already tinted) base, reproducing vanilla's
     *       two-element rendering for blocks like {@code grass_block}.</li>
     *   <li>All other faces: copied unchanged.</li>
     * </ul>
     * The returned model has its overlay map cleared because the overlay pixels
     * are now baked into the bases.
     */
    public BlockModel withTint(int tintArgb) {
        if (tintArgb == 0) return this;
        EnumMap<FaceDir, BufferedImage> result = new EnumMap<>(faces);
        for (FaceDir d : FaceDir.values()) {
            boolean baseTinted = tintedFaces.contains(d);
            boolean hasOverlay = overlays.containsKey(d);
            if (!baseTinted && !hasOverlay) continue;

            BufferedImage base = faces.get(d);
            if (baseTinted) {
                base = TintApplier.multiply(base, tintArgb);
            }
            if (hasOverlay) {
                BufferedImage tintedOverlay = TintApplier.multiply(overlays.get(d), tintArgb);
                base = TintApplier.composite(base, tintedOverlay);
            }
            result.put(d, base);
        }
        return new BlockModel(key, result, EnumSet.copyOf(tintedFaces),
                Collections.emptyMap(), parentChain, variantRotations);
    }
}
