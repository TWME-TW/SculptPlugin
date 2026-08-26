package dev.twme.sculpt.split;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import dev.twme.sculpt.core.FaceDir;

/**
 * Per-{@link FaceDir} mirror flip applied to the source block-face texture
 * <em>after</em> {@link SourceRotations} and before {@link TextureSplitter}
 * slices into tiles.
 *
 * <p>Why both rotation and flip? Rotations alone can only express 4 of the
 * 8 possible orientations of a square (the cyclic group C4). Adding a
 * mirror flip extends the reachable orientations to all 8 (the dihedral
 * group D4). Vanilla Minecraft's per-face UV conventions sometimes differ
 * by a flip (one face's image-X = world +Z, another's = world -Z), and
 * those mismatches manifest as edge-wrap mismatches between perpendicular
 * faces that no amount of rotation can fix — the wrong-rotated content
 * just moves to a different corner.
 *
 * <p>{@link Flip#NONE NONE} = identity. H mirrors image-X, V mirrors
 * image-Y, HV mirrors both (equivalent to a 180° rotation but exposed
 * for completeness so combinations with {@code SourceRotations} cover all
 * 8 orientations cleanly).
 *
 * <p><b>Why {@code DOWN} defaults to {@link Flip#V}:</b> the player-head
 * model used by ItemDisplay shares the same image-Y = -Z direction on
 * BOTTOM as vanilla's cube DOWN (verified empirically with
 * {@code furnace_side} chimney groove orientation and
 * {@code pumpkin_top} stem position on furnaces / jack o' lanterns).
 * {@link TextureSplitter#sourceTile sourceTile} uses {@code (cx, cz)} for
 * both UP and DOWN, which is correct for UP's image-Y = +Z but not for
 * DOWN's. Easier to express as a V-flip in {@code SourceFlips} than to
 * special-case the DOWN tile lookup.
 *
 * <p>Wired to the mutable {@code CURRENT} map for live tuning via
 * {@code /tessera debug sourceflip}. Fold dialed-in values into the
 * {@code DEFAULTS} block.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.split.SourceFlips}).
 */
public final class SourceFlips {

    public enum Flip {
        NONE, H, V, HV;

        public static Flip parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "none", "off", "" -> NONE;
                case "h", "horizontal", "flipx" -> H;
                case "v", "vertical", "flipy" -> V;
                case "hv", "vh", "both", "flipxy" -> HV;
                default -> throw new IllegalArgumentException("Unknown flip: " + s + " (try none|h|v|hv)");
            };
        }
    }

    private static final Map<FaceDir, Flip> DEFAULTS = new EnumMap<>(FaceDir.class);
    private static final EnumMap<FaceDir, Flip> CURRENT = new EnumMap<>(FaceDir.class);

    static {
        for (FaceDir f : FaceDir.values()) DEFAULTS.put(f, Flip.NONE);
        // DOWN: see class javadoc for rationale. Without this, faces that
        // use asymmetric V-direction detail (furnace chimneys, pumpkin stems)
        // render with their "up in the image" pointing the wrong way on
        // world DOWN.
        DEFAULTS.put(FaceDir.DOWN, Flip.V);
        CURRENT.putAll(DEFAULTS);
    }

    private SourceFlips() {}

    public static Flip of(FaceDir face) {
        Flip v = CURRENT.get(face);
        return v == null ? Flip.NONE : v;
    }

    public static Flip defaultOf(FaceDir face) {
        Flip v = DEFAULTS.get(face);
        return v == null ? Flip.NONE : v;
    }

    public static void set(FaceDir face, Flip flip) {
        CURRENT.put(face, flip);
    }

    public static void reset(FaceDir face) {
        CURRENT.put(face, DEFAULTS.get(face));
    }

    public static void resetAll() {
        CURRENT.clear();
        CURRENT.putAll(DEFAULTS);
    }
}
