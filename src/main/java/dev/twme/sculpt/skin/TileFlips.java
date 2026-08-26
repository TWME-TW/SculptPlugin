package dev.twme.sculpt.skin;

import dev.twme.sculpt.core.HeadFace;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Per-{@link HeadFace} mirror flip applied to a chunk tile <em>after</em>
 * {@link TileRotations} and before {@link SkinAssembler} paints it onto
 * the 64×64 skin canvas.
 *
 * <p>Why both rotation and flip? Rotations alone only express 4 of the 8
 * orientations of a square (the cyclic group C4); a flip extends to all 8
 * (the dihedral group D4). The player head's UV slot conventions don't
 * always match the corresponding cube face convention by a pure rotation
 * — for example, vanilla's UP face has image-Y = world +Z (south), but the
 * player head's TOP slot under {@code ItemDisplayTransform.NONE} has its
 * image-Y pointing toward world -Z. A 180° rotation flips both axes; a
 * single-axis flip is the right fix when only one disagrees.
 *
 * <p>Diagnostic test: if {@code set(face, X)} for X = 90/180/270 only
 * moves the bad edges around without fixing the wrap with neighbours, the
 * slot needs a flip instead. Try {@code set(face, V)}.
 *
 * <p>Bake-time only; auto-invalidates the registry on change (combined
 * with {@link TileRotations#consumeStale}).
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.skin.TileFlips}).
 */
public final class TileFlips {

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

    private static final Map<HeadFace, Flip> DEFAULTS = new EnumMap<>(HeadFace.class);
    private static final EnumMap<HeadFace, Flip> CURRENT = new EnumMap<>(HeadFace.class);

    static {
        for (HeadFace f : HeadFace.values()) DEFAULTS.put(f, Flip.NONE);
        CURRENT.putAll(DEFAULTS);
    }

    private TileFlips() {}

    public static Flip of(HeadFace face) {
        Flip v = CURRENT.get(face);
        return v == null ? Flip.NONE : v;
    }

    public static Flip defaultOf(HeadFace face) {
        Flip v = DEFAULTS.get(face);
        return v == null ? Flip.NONE : v;
    }

    public static void set(HeadFace face, Flip flip) {
        Flip prev = CURRENT.put(face, flip);
        if (prev != flip) TileRotations.markStale();
    }

    public static void reset(HeadFace face) {
        Flip prev = CURRENT.put(face, DEFAULTS.get(face));
        if (!Objects.equals(prev, CURRENT.get(face))) TileRotations.markStale();
    }

    public static void resetAll() {
        CURRENT.clear();
        CURRENT.putAll(DEFAULTS);
        TileRotations.markStale();
    }
}
