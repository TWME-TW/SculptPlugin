package dev.twme.sculpt.editor.gizmo;

import org.joml.Vector3d;

/**
 * One draggable handle of the transform gizmo.
 *
 * <p>A handle is a direction, a kind, and a base color. Its geometry lives in
 * {@link Gizmo}, so the hit test and the preview read the same constants.</p>
 */
public enum GizmoHandle {

    /** Moves along east-west. */
    MOVE_X(Kind.MOVE, new Vector3d(1, 0, 0), 0xE0E05A5A),
    /** Moves along up-down. */
    MOVE_Y(Kind.MOVE, new Vector3d(0, 1, 0), 0xE05FBA6B),
    /** Moves along north-south. */
    MOVE_Z(Kind.MOVE, new Vector3d(0, 0, 1), 0xE05A7BE0),
    /** Moves freely in the horizontal plane. */
    MOVE_XZ(Kind.MOVE_PLANE, new Vector3d(0, 1, 0), 0xE0E8C96D),
    /** Rotates around the vertical axis. */
    ROTATE_Y(Kind.ROTATE, new Vector3d(0, 1, 0), 0xE0E8C96D),
    /** Mirrors east-west. */
    MIRROR_X(Kind.MIRROR, new Vector3d(1, 0, 0), 0xE0E05A5A),
    /** Mirrors north-south. */
    MIRROR_Z(Kind.MIRROR, new Vector3d(0, 0, 1), 0xE05A7BE0);

    /** What a handle does when dragged. */
    public enum Kind {
        /** Translate along the handle direction. */
        MOVE,
        /** Translate freely within the plane perpendicular to the direction. */
        MOVE_PLANE,
        /** Rotate around the handle axis. */
        ROTATE,
        /** Toggle a mirror axis on release. */
        MIRROR
    }

    private final Kind kind;
    private final Vector3d direction;
    private final int color;

    GizmoHandle(final Kind kind, final Vector3d direction, final int color) {
        this.kind = kind;
        this.direction = direction;
        this.color = color;
    }

    public Kind kind() {
        return kind;
    }

    /** A copy of the handle's axis, or the plane normal for a plane handle. */
    public Vector3d direction() {
        return new Vector3d(direction);
    }

    /** The handle's base color; the preview dims or brightens it for state. */
    public int color() {
        return color;
    }

    public boolean isMirror() {
        return kind == Kind.MIRROR;
    }

    /** The handle's name for status text. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
