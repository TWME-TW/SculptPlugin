package dev.twme.sculpt.editor.preview;

/** The editor's color language (ARGB). */
public final class Colors {

    /** Neutral cursor. */
    public static final int CURSOR = 0xE0FFFFFF;
    /** Adding material. */
    public static final int ADD = 0xE05FBA6B;
    /** Removing material. */
    public static final int REMOVE = 0xE0CE5F4E;
    /** Repainting or replacing material. */
    public static final int PAINT = 0xE07AB8E8;
    /** Selections. */
    public static final int SELECT = 0xE0E8C96D;
    /** Moving or transforming. */
    public static final int TRANSFORM = 0xE0B08CE8;
    /** Protected, blocked, or unavailable. */
    public static final int BLOCKED = 0xE0808080;

    private Colors() {
    }

    /** The same color with a new alpha channel. */
    public static int withAlpha(final int argb, final int alpha) {
        return (alpha & 0xFF) << 24 | (argb & 0x00FFFFFF);
    }

    /** A translucent face color derived from an edge color. */
    public static int face(final int argb) {
        return withAlpha(argb, 0x50);
    }
}
