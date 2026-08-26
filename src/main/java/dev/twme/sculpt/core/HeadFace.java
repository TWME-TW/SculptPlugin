package dev.twme.sculpt.core;

/**
 * UV regions of the standard 64×64 player-head skin (no hat layer used).
 * Each region is 8×8 px; a Sculpt chunk tile (typically 4×4 px for grid=4)
 * is center-pasted into its slot by the SkinAssembler (Phase 3).
 *
 * <p>The {@link #BOTTOM} face's U axis is sampled in reverse by the vanilla
 * renderer; SkinAssembler compensates by pre-mirroring the BOTTOM tile
 * horizontally during paint, so the canonical UV rectangle below describes
 * the painted region (after the pre-mirror), not the raw rendering order.
 *
 * <p><b>Canonical face mapping</b> (must match
 * {@code dev.twme.sculpt.assemble.FaceRotations} at bake time):
 * <pre>
 *   HeadFace  → world FaceDir
 *   TOP       → UP
 *   BOTTOM    → DOWN
 *   FRONT     → SOUTH
 *   BACK      → NORTH
 *   RIGHT     → WEST      &lt;-- X axis mirrored
 *   LEFT      → EAST      &lt;-- X axis mirrored
 * </pre>
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.core.HeadFace}).
 * See {@code DEVELOPMENT_PLAN.md} §4.2.
 */
public enum HeadFace {
    /** Top of head — looking down onto the cube. */
    TOP    ( 8,  0, 16,  8),
    /** Bottom of head — vanilla samples this with U flipped; SkinAssembler pre-mirrors. */
    BOTTOM (16,  0, 24,  8),
    /** Wearer's right cheek — sampled by the model's {@code west} (-X) cube
     *  face, so under canonical rotation this slot's pixels render on
     *  world west. (With Steve facing +Z south, the wearer's right side is
     *  at -X.) */
    RIGHT  ( 0,  8,  8, 16),
    /** Wearer's face — sampled by the model's {@code south} (+Z) cube face;
     *  renders on world south under canonical rotation. */
    FRONT  ( 8,  8, 16, 16),
    /** Wearer's left cheek — sampled by the model's {@code east} (+X) cube
     *  face; renders on world east under canonical rotation. */
    LEFT   (16,  8, 24, 16),
    /** Back of head. */
    BACK   (24,  8, 32, 16);

    /** Inclusive-exclusive UV rect on the 64×64 skin canvas. */
    public final int u0, v0, u1, v1;

    HeadFace(int u0, int v0, int u1, int v1) {
        this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
    }

    public int width()  { return u1 - u0; }
    public int height() { return v1 - v0; }

    /**
     * Canonical packing order: when packing N≤6 chunks onto one head, slot N
     * is {@code PACK_ORDER[N]}. Lifted from Mosaikin so frame-0 of an
     * animated lifetime always lands on FRONT (most visible to a player
     * looking at the entity from the +Z side).
     */
    public static final HeadFace[] PACK_ORDER = {
            FRONT, BACK, RIGHT, LEFT, TOP, BOTTOM
    };
}
