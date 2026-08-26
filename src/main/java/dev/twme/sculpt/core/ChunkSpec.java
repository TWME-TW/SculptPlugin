package dev.twme.sculpt.core;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/**
 * One sub-block chunk to be rendered as a player-head ItemDisplay. Holds the
 * chunk's grid position and the texture tiles that should appear on each of
 * its six faces.
 *
 * <p>Built by {@code split.TextureSplitter} from the parent {@code BlockModel}'s
 * face textures, then consumed by {@code skin.HeadSkinPacker} (groups them
 * onto 64×64 head skins) and {@code assemble.SculptFactory} (spawns the
 * matching ItemDisplay).
 *
 * <p>{@link #tiles} stores ALL 6 {@link FaceDir} tiles — every chunk in the
 * N³ lattice gets correct tiles on all faces.  When the player removes an
 * adjacent chunk the previously-hidden face becomes visible and must show
 * the correct texture, not a filler.  Interior chunks are fully baked so
 * that any surface exposed by sculpting reveals proper world-block texels.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.core.ChunkSpec}).
 * See {@code DEVELOPMENT_PLAN.md} §3.
 */
public final class ChunkSpec {

    private final ChunkCoord coord;
    /** All 6 FaceDir → tile.  Outward-checked by {@link #outwardFaces}. */
    private final EnumMap<FaceDir, BufferedImage> tiles;
    private final EnumSet<FaceDir> outwardFaces;

    public ChunkSpec(ChunkCoord coord, Map<FaceDir, BufferedImage> tiles) {
        this.coord = Objects.requireNonNull(coord, "coord");
        this.tiles = new EnumMap<>(FaceDir.class);
        this.tiles.putAll(tiles);
        if (this.tiles.size() != 6) {
            throw new IllegalArgumentException(
                    "ChunkSpec must have all 6 face tiles, got " + this.tiles.size());
        }
        // Outward faces are those on the block surface (used by geometry layer).
        EnumSet<FaceDir> outward = EnumSet.noneOf(FaceDir.class);
        for (FaceDir d : FaceDir.values()) {
            if (d.isOutwardAt(coord.x(), coord.y(), coord.z(),
                    /* gridN is unknown here; we just store the face set
                       directly.  Callers that need this gate should use
                       FaceDir.isOutwardAt or SculptGeometry.outwardFaces.) */
                    -1)) {
                // can't compute without gridN — see below
            }
        }
        // gridN is not stored on ChunkSpec.  Callers that need the
        // outward-face gate use SculptGeometry.outwardFaces(coord, gridN)
        // directly; we keep outwardFaces for backward compat only.
        this.outwardFaces = EnumSet.noneOf(FaceDir.class);
        // (deprecated filler; kept only so existing callers of
        // outwardFaces() don't crash.  HeadSkinPacker.buildFaceTiles
        // no longer uses it.)
    }

    public ChunkCoord coord() { return coord; }

    /** @return outward faces (for backward compat; prefer SculptGeometry.outwardFaces). */
    public EnumSet<FaceDir> outwardFaces() { return EnumSet.copyOf(outwardFaces); }

    /** @return the tile for {@code face} (always non-null; all 6 are populated). */
    public BufferedImage tile(FaceDir face) { return tiles.get(face); }

    /** @return an unmodifiable view of all 6 tiles. */
    public Map<FaceDir, BufferedImage> tiles() { return Map.copyOf(tiles); }

    /** Number of outward faces. */
    public int outwardCount() { return outwardFaces.size(); }
}
