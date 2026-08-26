package dev.twme.sculpt.split;

import java.util.EnumMap;
import java.util.Map;

import dev.twme.sculpt.core.FaceDir;

/**
 * Per-{@link FaceDir} rotation (degrees, multiple of 90) applied to the
 * <em>source block-face texture</em> before {@link TextureSplitter} slices
 * it into tiles.
 *
 * <p>This is the "rotate the whole block face" knob. Distinct from
 * {@code skin.TileRotations} (Phase 3.3), which rotates each individual
 * chunk's tile in-plane within its head-face slot. If the entire UP face of
 * stone looks rotated 90° when rendered (i.e. the texture's "north" edge
 * ends up on the east side of the block), set
 * {@code set(UP, 90)} (or 270 — try both signs) and re-bake.
 *
 * <p>Why this exists: vanilla Minecraft has fiddly per-face UV conventions
 * that don't always match the obvious mental model (DOWN's image-Y axis
 * goes one way; UP's goes the other; etc.). The
 * {@link TextureSplitter#sourceTile sourceTile} mapping is a best-guess;
 * per-face overrides let you fix specific axes without rewriting the
 * splitter.
 *
 * <p>Implementation note: rotating the source <em>then</em> slicing is
 * mathematically equivalent to permuting which chunk grabs which tile, but
 * far simpler — one {@link java.awt.image.BufferedImage} rotation per face
 * vs. a permuted {@code sourceTile} lookup.
 *
 * <p>Wired to the mutable {@code CURRENT} map so {@code /tessera debug}
 * can tune values live. Once values are dialed in, fold them into the
 * {@code DEFAULTS} block (per {@code DEVELOPMENT_PLAN.md} §11 "Debugging").
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.split.SourceRotations}).
 */
public final class SourceRotations {

    private static final Map<FaceDir, Integer> DEFAULTS = new EnumMap<>(FaceDir.class);
    private static final EnumMap<FaceDir, Integer> CURRENT = new EnumMap<>(FaceDir.class);

    static {
        for (FaceDir f : FaceDir.values()) DEFAULTS.put(f, 0);
        CURRENT.putAll(DEFAULTS);
    }

    private SourceRotations() {}

    /** Current rotation in degrees (always a multiple of 90 in [0, 360)). */
    public static int of(FaceDir face) {
        Integer v = CURRENT.get(face);
        return v == null ? 0 : v;
    }

    public static int defaultOf(FaceDir face) {
        Integer v = DEFAULTS.get(face);
        return v == null ? 0 : v;
    }

    /**
     * Set a live override. Input is normalized to {@code [0, 360)} and must
     * be a multiple of 90.
     */
    public static void set(FaceDir face, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized % 90 != 0) {
            throw new IllegalArgumentException("SourceRotation must be a multiple of 90, got " + degrees);
        }
        CURRENT.put(face, normalized);
    }

    public static void reset(FaceDir face) {
        CURRENT.put(face, DEFAULTS.get(face));
    }

    public static void resetAll() {
        CURRENT.clear();
        CURRENT.putAll(DEFAULTS);
    }
}
