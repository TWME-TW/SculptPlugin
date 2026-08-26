package dev.twme.sculpt.core;

import org.joml.Vector3i;

/**
 * One of the six block-local face directions. Distinct from {@link HeadFace},
 * which is the player-head's UV layout — the {@code FaceDir → HeadFace}
 * mapping is what the assemble layer's right-rotation table picks.
 *
 * <p>Axis convention matches Minecraft block-space: {@code +X = east},
 * {@code +Y = up}, {@code +Z = south}. {@link #DOWN} corresponds to NBT
 * {@code "down"}, etc.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.core.FaceDir}).
 * See {@code DEVELOPMENT_PLAN.md} §4.1.
 */
public enum FaceDir {
    DOWN ( 0, -1,  0),
    UP   ( 0,  1,  0),
    NORTH( 0,  0, -1),
    SOUTH( 0,  0,  1),
    WEST (-1,  0,  0),
    EAST ( 1,  0,  0);

    public final int dx, dy, dz;

    FaceDir(int dx, int dy, int dz) {
        this.dx = dx; this.dy = dy; this.dz = dz;
    }

    public Vector3i normal() {
        return new Vector3i(dx, dy, dz);
    }

    /** Convenience overload of {@link #isOutwardAt(int, int, int, int)}. */
    public boolean isOutwardAt(ChunkCoord c, int gridN) {
        return isOutwardAt(c.x(), c.y(), c.z(), gridN);
    }

    /**
     * True iff this face is on the outside of an {@code N×N×N} cube whose
     * chunk at {@code (x,y,z)} (0-indexed) is being queried. Boundary chunks
     * expose one or more faces to the world.
     */
    public boolean isOutwardAt(int x, int y, int z, int gridN) {
        int last = gridN - 1;
        return switch (this) {
            case DOWN  -> y == 0;
            case UP    -> y == last;
            case NORTH -> z == 0;
            case SOUTH -> z == last;
            case WEST  -> x == 0;
            case EAST  -> x == last;
        };
    }

    /**
     * Vanilla block-face directional shading factor, sourced from
     * {@code ClientLevel.getShade()} in the decompiled 1.21.4 client.
     * Applied to quads whose {@code BakedQuad.isShade()} is true. Nether
     * values (all faces 0.9) are omitted; Sculpt targets Overworld only in v1.
     */
    public float shade() { return switch (this) {
        case DOWN -> 0.5f; case UP -> 1.0f;
        case NORTH, SOUTH -> 0.8f;
        case WEST, EAST -> 0.6f;
    }; }

    public FaceDir opposite() { return switch (this) {
        case DOWN -> UP; case UP -> DOWN;
        case NORTH -> SOUTH; case SOUTH -> NORTH;
        case WEST -> EAST; case EAST -> WEST;
    }; }

    /** Lower-case JSON name as used in vanilla block model {@code "faces"} maps. */
    public String jsonName() {
        return name().toLowerCase();
    }

    public static FaceDir fromJson(String s) {
        return valueOf(s.toUpperCase());
    }
}
