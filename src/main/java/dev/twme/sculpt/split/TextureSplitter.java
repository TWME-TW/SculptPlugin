package dev.twme.sculpt.split;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.twme.sculpt.assets.model.BlockModel;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.ChunkSpec;
import dev.twme.sculpt.core.FaceDir;

/**
 * Slices a {@link BlockModel}'s 6 face textures into N×N tiles and emits one
 * {@link ChunkSpec} per cell in the N³ lattice.
 *
 * <p>Coordinate mapping (the tricky bit): Minecraft block-space is
 * right-handed with +X east, +Y up, +Z south. Vanilla face textures are
 * 16×16 PNGs whose U axis (image X) and V axis (image Y) are oriented
 * per-face. For our sub-block split we want the chunk at grid coord
 * {@code (cx,cy,cz)} to sample the (sx,sy) tile of each outward face such
 * that the image lines up across adjacent chunks — i.e. neighbouring
 * chunks' tiles are pixel-adjacent.
 *
 * <p>Per-face source-tile mapping. Sides match vanilla
 * {@code BlockElementFace.defaultFaceUV} verbatim. UP and DOWN match
 * each other rather than vanilla's mismatched cube up/down convention,
 * because the player-head model used by ItemDisplay rendering shares
 * the skin layout's image-Y = +Z direction on both TOP and BOTTOM
 * (vanilla's cube {@code image-Y = -Z} on DOWN does not apply to the
 * skull model). The DOWN's image-Y reversal is folded into
 * {@link SourceFlips}'s default DOWN=V instead of diverging the lookup
 * here.
 * <ul>
 *   <li>UP    (+Y) — tileX = cx,             tileY = cz             (image-X = +X, image-Y = +Z)</li>
 *   <li>DOWN  (-Y) — tileX = cx,             tileY = cz             (image-X = +X, image-Y = +Z, head-model convention)</li>
 *   <li>NORTH (-Z) — tileX = (N-1) − cx,     tileY = (N-1) − cy     (image-X = -X, image-Y = -Y)</li>
 *   <li>SOUTH (+Z) — tileX = cx,             tileY = (N-1) − cy     (image-X = +X, image-Y = -Y)</li>
 *   <li>EAST  (+X) — tileX = (N-1) − cz,     tileY = (N-1) − cy     (image-X = -Z, image-Y = -Y)</li>
 *   <li>WEST  (-X) — tileX = cz,             tileY = (N-1) − cy     (image-X = +Z, image-Y = -Y)</li>
 * </ul>
 * If an axis flip turns out wrong on a given face during step 11 tuning, fix
 * it in {@link #sourceTile} alone — every other module references chunks by
 * {@link ChunkCoord} not by source UV.
 *
 * <p>All N³ chunks are baked, including interior chunks. Every chunk carries
 * correct tiles on all 6 faces so that any face exposed by removing an
 * adjacent chunk shows the right world-block texture. Total chunk count
 * = {@code n³} (= 64 for n=4, 512 for n=8, 4096 for n=16).
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.split.TextureSplitter}).
 */
public final class TextureSplitter {

    public List<ChunkSpec> split(BlockModel model, int gridN) {
        if (gridN < 1) throw new IllegalArgumentException("gridN must be >= 1");
        List<ChunkCoord> coords = new ArrayList<>(gridN * gridN * gridN);
        for (int x = 0; x < gridN; x++) {
            for (int y = 0; y < gridN; y++) {
                for (int z = 0; z < gridN; z++) {
                    coords.add(new ChunkCoord(x, y, z));
                }
            }
        }
        return split(model, gridN, coords);
    }

    /**
     * Split only the requested cells. This is the runtime-bake path: a missing
     * cell can bake its 2x2x2 sibling group without materialising or uploading
     * the rest of a high-resolution grid.
     */
    public List<ChunkSpec> split(BlockModel model, int gridN,
                                 Collection<ChunkCoord> requestedCoords) {
        if (gridN < 1) throw new IllegalArgumentException("gridN must be >= 1");
        if (requestedCoords == null) {
            throw new IllegalArgumentException("requestedCoords must not be null");
        }

        List<ChunkCoord> coords = new ArrayList<>(requestedCoords.size());
        Set<ChunkCoord> seen = new HashSet<>();
        for (ChunkCoord coord : requestedCoords) {
            if (coord == null) throw new IllegalArgumentException("requestedCoords contains null");
            if (coord.x() >= gridN || coord.y() >= gridN || coord.z() >= gridN) {
                throw new IllegalArgumentException("ChunkCoord outside gridN=" + gridN
                        + ": " + coord.asKey());
            }
            if (seen.add(coord)) coords.add(coord);
        }

        final PreparedGrid prepared = prepare(model, gridN);

        List<ChunkSpec> out = new ArrayList<>(coords.size());
        for (ChunkCoord coord : coords) {
            out.add(prepared.cell(coord));
        }
        return out;
    }

    /**
     * Preprocess one material/resolution once, then cheaply resolve any number
     * of cell coordinates. TextDisplay rendering uses this to avoid repeating
     * six whole-face rotations and splits for every occupied octree leaf.
     */
    public PreparedGrid prepare(final BlockModel model, final int gridN) {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (gridN < 1) throw new IllegalArgumentException("gridN must be >= 1");

        // Pre-slice each face into a gridN x gridN tile array after
        // applying the per-face SourceRotations + SourceFlips. Both are
        // user-tunable "rotate/mirror the whole block face" knobs,
        // distinct from per-tile in-plane rotation (TileRotations, Phase
        // 3.3). Order: rotate THEN flip.
        EnumMap<FaceDir, BufferedImage[][]> tilesByFace = new EnumMap<>(FaceDir.class);
        for (FaceDir d : FaceDir.values()) {
            BufferedImage rotated = rotate90Multiples(model.face(d), SourceRotations.of(d));
            BufferedImage flipped = applyFlip(rotated, SourceFlips.of(d));
            tilesByFace.put(d, sliceFace(flipped, gridN));
        }
        return new PreparedGrid(gridN, tilesByFace);
    }

    /** Immutable pre-sliced face grid. */
    public static final class PreparedGrid {
        private final int gridN;
        private final EnumMap<FaceDir, BufferedImage[][]> tilesByFace;

        private PreparedGrid(
                final int gridN,
                final EnumMap<FaceDir, BufferedImage[][]> tilesByFace) {
            this.gridN = gridN;
            this.tilesByFace = tilesByFace;
        }

        public int gridN() {
            return gridN;
        }

        public ChunkSpec cell(final ChunkCoord coord) {
            if (coord == null || coord.x() >= gridN || coord.y() >= gridN
                    || coord.z() >= gridN) {
                throw new IllegalArgumentException(
                    "ChunkCoord outside gridN=" + gridN + ": " + coord);
            }
            final EnumMap<FaceDir, BufferedImage> allTiles =
                new EnumMap<>(FaceDir.class);
            for (final FaceDir direction : FaceDir.values()) {
                final int[] source = sourceTile(
                    direction, coord.x(), coord.y(), coord.z(), gridN);
                allTiles.put(direction,
                    tilesByFace.get(direction)[source[0]][source[1]]);
            }
            return new ChunkSpec(coord, allTiles);
        }
    }

    /** Returns a {@code [tileX][tileY]} array of {@code (16/n)×(16/n)} sub-images. */
    private static BufferedImage[][] sliceFace(BufferedImage face, int n) {
        int size = face.getWidth();
        if (size != face.getHeight()) {
            throw new IllegalArgumentException("Face texture must be square, got "
                    + size + "x" + face.getHeight());
        }
        if (size % n != 0) {
            throw new IllegalArgumentException("Face size " + size + " not divisible by gridN " + n);
        }
        int tileSize = size / n;
        BufferedImage[][] tiles = new BufferedImage[n][n];
        for (int tx = 0; tx < n; tx++) {
            for (int ty = 0; ty < n; ty++) {
                tiles[tx][ty] = face.getSubimage(tx * tileSize, ty * tileSize, tileSize, tileSize);
            }
        }
        return tiles;
    }

    /** Apply horizontal/vertical/both mirror per {@link SourceFlips}. */
    private static BufferedImage applyFlip(BufferedImage src, SourceFlips.Flip flip) {
        if (flip == SourceFlips.Flip.NONE) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            int sy = (flip == SourceFlips.Flip.V || flip == SourceFlips.Flip.HV) ? (h - 1 - y) : y;
            for (int x = 0; x < w; x++) {
                int sx = (flip == SourceFlips.Flip.H || flip == SourceFlips.Flip.HV) ? (w - 1 - x) : x;
                out.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return out;
    }

    /**
     * 90° multiple rotation of a square source texture. Mirrors the same
     * function in {@code skin.SkinAssembler} (Phase 3.3) — kept private
     * here so {@code split} has no dep on the skin package.
     */
    private static BufferedImage rotate90Multiples(BufferedImage src, int degrees) {
        int turns = (((degrees % 360) + 360) % 360) / 90;
        if (turns == 0) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        int outW = (turns % 2 == 1) ? h : w;
        int outH = (turns % 2 == 1) ? w : h;
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int nx, ny;
                switch (turns) {
                    case 1 -> { nx = h - 1 - y; ny = x; }
                    case 2 -> { nx = w - 1 - x; ny = h - 1 - y; }
                    case 3 -> { nx = y;         ny = w - 1 - x; }
                    default -> { nx = x; ny = y; }
                }
                out.setRGB(nx, ny, rgb);
            }
        }
        return out;
    }

    private static int[] sourceTile(FaceDir d, int cx, int cy, int cz, int n) {
        int last = n - 1;
        return switch (d) {
            // UP uses (cx, cz) — image-Y = +Z. DOWN's mapping is also
            // (cx, cz) here, but the head model's BOTTOM UV actually has
            // image-Y = -Z (matching vanilla cube DOWN, not TOP); we
            // compensate via a default V-flip in SourceFlips.DOWN rather
            // than diverging the lookup here. Sides match the cube
            // convention verbatim.
            case UP    -> new int[] { cx,        cz };
            case DOWN  -> new int[] { cx,        cz };
            case NORTH -> new int[] { last - cx, last - cy };
            case SOUTH -> new int[] { cx,        last - cy };
            case EAST  -> new int[] { last - cz, last - cy };
            case WEST  -> new int[] { cz,        last - cy };
        };
    }
}
