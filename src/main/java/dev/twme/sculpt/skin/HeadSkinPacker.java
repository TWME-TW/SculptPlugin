package dev.twme.sculpt.skin;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.twme.sculpt.core.ChunkSpec;
import dev.twme.sculpt.core.FaceDir;
import dev.twme.sculpt.core.HeadFace;
import dev.twme.sculpt.util.Hashing;

/**
 * Builds one {@link HeadSkin} per {@link ChunkSpec} and dedupes by content
 * hash so identical chunks share a single MineSkin upload.
 *
 * <p>Each chunk's tile map is built by mapping each {@link HeadFace} back
 * to its {@link FaceDir} and pulling the chunk's tile for that direction.
 * All 6 HeadFace slots get correct world-block tiles (no filler) so that
 * any face exposed by removing an adjacent chunk shows proper texels.
 *
 * <p>For a uniform 16×16 stone block at gridN=4, every interior chunk
 * (coords 1..2 on all axes) is identical, every face-center chunk (one
 * axis on boundary) is identical, every edge chunk (two axes on boundary)
 * is identical, and every corner chunk (three axes on boundary) is
 * identical.  At gridN=4 the dedup count for a uniform block is:
 * <ul>
 *   <li>1 head for the 8 interior chunks (all 6 faces identical)</li>
 *   <li>1 head for the 24 face-center chunks (1 unique face type)</li>
 *   <li>1 head for the 24 edge chunks (2 unique face types)</li>
 *   <li>1 head for the 8 corner chunks (3 unique face types)</li>
 * </ul>
 * Total: 4 unique skin uploads for stone at gridN=4.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.skin.HeadSkinPacker}).
 */
public final class HeadSkinPacker {

    public record Result(List<HeadSkin> uniqueHeads, Map<ChunkSpec, HeadSkin> chunkToHead) {}

    public Result pack(List<ChunkSpec> chunks) {
        List<ChunkSpec> ordered = new ArrayList<>(chunks);
        ordered.sort(Comparator.comparing(ChunkSpec::coord));

        Map<String, HeadSkin> dedup = new HashMap<>();
        Map<ChunkSpec, HeadSkin> mapping = new LinkedHashMap<>();
        List<HeadSkin> uniqueHeads = new ArrayList<>();

        for (ChunkSpec chunk : ordered) {
            EnumMap<HeadFace, BufferedImage> faceTiles = buildFaceTiles(chunk);
            String hash = hashFaceTiles(faceTiles);

            HeadSkin head = dedup.computeIfAbsent(hash, h -> {
                HeadSkin newHead = new HeadSkin(HeadSkin.idFromHash(h), h, faceTiles);
                uniqueHeads.add(newHead);
                return newHead;
            });
            head.addChunk(chunk);
            mapping.put(chunk, head);
        }

        return new Result(uniqueHeads, mapping);
    }

    private static EnumMap<HeadFace, BufferedImage> buildFaceTiles(ChunkSpec chunk) {
        EnumMap<HeadFace, BufferedImage> out = new EnumMap<>(HeadFace.class);
        for (HeadFace hf : HeadFace.values()) {
            FaceDir matching = headFaceToFaceDir(hf);
            // All 6 FaceDirs have correct tiles — no filler needed.
            out.put(hf, chunk.tile(matching));
        }
        return out;
    }

    private static String hashFaceTiles(Map<HeadFace, BufferedImage> tiles) {
        List<String> parts = new ArrayList<>(6);
        for (HeadFace hf : HeadFace.PACK_ORDER) {
            BufferedImage tile = tiles.get(hf);
            parts.add(tile == null ? "empty" : Hashing.sha256OfImage(tile));
        }
        return Hashing.sha256OfStrings(parts);
    }

    private static FaceDir primaryOutward(ChunkSpec chunk) {
        for (FaceDir d : FaceDir.values()) {
            if (chunk.outwardFaces().contains(d)) return d;
        }
        throw new IllegalStateException("ChunkSpec has no outward faces: " + chunk.coord());
    }

    /**
     * Inverse of {@link #faceDirToHeadFace}. Lives here so the canonical
     * mapping between block-local {@link FaceDir} and skin-local
     * {@link HeadFace} stays in one place.
     *
     * <p><b>RIGHT↔WEST, LEFT↔EAST is intentional</b> and matches the
     * vanilla player-head model's cube UVs. The model's {@code east}
     * (+X) face samples skin pixels {@code (16, 8)-(24, 16)} — the LEFT
     * slot (wearer's left cheek; with Steve facing +Z south, the wearer's
     * left side is at +X). Symmetrically, the model's {@code west} (-X)
     * face samples the RIGHT slot. Under canonical rotation, world east
     * shows LEFT-slot pixels and world west shows RIGHT-slot pixels, so
     * the outward EAST tile must be packed into LEFT and the outward
     * WEST tile into RIGHT. Mirroring this naively (LEFT→WEST,
     * RIGHT→EAST) is invisible on uniform-textured blocks but produces
     * wrong textures on the top/bottom rows of east/west faces for
     * non-uniform blocks like oak_log: those chunks fill RIGHT with the
     * EAST source bark, which the renderer then shows on world west,
     * while LEFT receives the cross-section filler that the renderer
     * shows on world east.
     */
    public static FaceDir headFaceToFaceDir(HeadFace f) {
        return switch (f) {
            case TOP    -> FaceDir.UP;
            case BOTTOM -> FaceDir.DOWN;
            case FRONT  -> FaceDir.SOUTH;
            case BACK   -> FaceDir.NORTH;
            case RIGHT  -> FaceDir.WEST;
            case LEFT   -> FaceDir.EAST;
        };
    }

    public static HeadFace faceDirToHeadFace(FaceDir d) {
        return switch (d) {
            case UP    -> HeadFace.TOP;
            case DOWN  -> HeadFace.BOTTOM;
            case SOUTH -> HeadFace.FRONT;
            case NORTH -> HeadFace.BACK;
            case EAST  -> HeadFace.LEFT;
            case WEST  -> HeadFace.RIGHT;
        };
    }
}
