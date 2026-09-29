package dev.twme.sculpt.plugin;

import java.util.List;

/** Canonical permission nodes for Sculpt commands and player capabilities. */
public final class SculptPermissions {

    public static final String COMMAND_ALL = "sculpt.command.*";

    public static final String EDIT = "sculpt.command.edit";

    public static final String RESOLUTION_PREFIX = "sculpt.command.resolution.";
    public static final String RESOLUTION_ALL = RESOLUTION_PREFIX + "*";
    public static final String CONVERT = "sculpt.command.convert";
    public static final String REPLACE = "sculpt.command.replace";
    public static final String RELIGHT = "sculpt.command.relight";
    public static final String UNDO = "sculpt.command.undo";

    public static final String FILL_PREFIX = "sculpt.command.fill.";
    public static final String FILL_ALL = FILL_PREFIX + "*";
    public static final String FILL_BARRIER = FILL_PREFIX + "barrier";
    public static final String FILL_SHULKER = FILL_PREFIX + "shulker";
    public static final String FILL_NULL = FILL_PREFIX + "null";

    public static final String DISPLAY_PREFIX = "sculpt.command.display.";
    public static final String DISPLAY_ALL = DISPLAY_PREFIX + "*";
    public static final String DISPLAY_HEAD = DISPLAY_PREFIX + "head";
    public static final String DISPLAY_TEXTDISPLAY = DISPLAY_PREFIX + "textdisplay";
    public static final String DISPLAY_AUTO = DISPLAY_PREFIX + "auto";

    /** Editor tools; see {@link dev.twme.sculpt.editor.ToolId}. */
    public static final String EDITOR_PREFIX = "sculpt.editor.";
    public static final String EDITOR_ALL = EDITOR_PREFIX + "*";
    public static final String EDITOR_SCULPT = EDITOR_PREFIX + "sculpt";
    public static final String EDITOR_BRUSH = EDITOR_PREFIX + "brush";
    public static final String EDITOR_SMOOTH = EDITOR_PREFIX + "smooth";
    public static final String EDITOR_PAINT = EDITOR_PREFIX + "paint";
    public static final String EDITOR_SELECT = EDITOR_PREFIX + "select";
    public static final String EDITOR_TRANSFORM = EDITOR_PREFIX + "transform";
    public static final String EDITOR_SHAPE = EDITOR_PREFIX + "shape";
    public static final String EDITOR_BLUEPRINT = EDITOR_PREFIX + "blueprint";
    public static final List<String> EDITOR_TOOLS = List.of(
        EDITOR_SCULPT, EDITOR_BRUSH, EDITOR_SMOOTH, EDITOR_PAINT, EDITOR_SELECT,
        EDITOR_TRANSFORM, EDITOR_SHAPE, EDITOR_BLUEPRINT
    );

    public static final String BLUEPRINT_PREFIX = "sculpt.command.blueprint.";
    public static final String BLUEPRINT_ALL = BLUEPRINT_PREFIX + "*";
    public static final String BLUEPRINT_SAVE = BLUEPRINT_PREFIX + "save";
    public static final String BLUEPRINT_LIST = BLUEPRINT_PREFIX + "list";
    public static final String BLUEPRINT_DELETE = BLUEPRINT_PREFIX + "delete";
    public static final String BLUEPRINT_RENAME = BLUEPRINT_PREFIX + "rename";
    public static final String BLUEPRINT_DOWNLOAD = BLUEPRINT_PREFIX + "download";
    public static final String BLUEPRINT_BIND = BLUEPRINT_PREFIX + "bind";
    public static final String BLUEPRINT_UNBIND = BLUEPRINT_PREFIX + "unbind";
    public static final String BLUEPRINT_GIVE = BLUEPRINT_PREFIX + "give";
    public static final String BLUEPRINT_SETTINGS = BLUEPRINT_PREFIX + "settings";
    public static final String BLUEPRINT_PUBLISH = BLUEPRINT_PREFIX + "publish";
    public static final String BLUEPRINT_UNPUBLISH = BLUEPRINT_PREFIX + "unpublish";
    public static final String BLUEPRINT_EXPORT = BLUEPRINT_PREFIX + "export";
    public static final String BLUEPRINT_IMPORT = BLUEPRINT_PREFIX + "import";
    public static final List<String> BLUEPRINT_PERMISSIONS = List.of(
        BLUEPRINT_SAVE, BLUEPRINT_LIST, BLUEPRINT_DELETE, BLUEPRINT_RENAME,
        BLUEPRINT_DOWNLOAD, BLUEPRINT_BIND, BLUEPRINT_UNBIND, BLUEPRINT_GIVE,
        BLUEPRINT_SETTINGS, BLUEPRINT_PUBLISH, BLUEPRINT_UNPUBLISH,
        BLUEPRINT_EXPORT, BLUEPRINT_IMPORT
    );

    public static final String HEADS = "sculpt.command.heads";

    public static final String ADMIN_PREFIX = "sculpt.command.admin.";
    public static final String ADMIN_ALL = ADMIN_PREFIX + "*";
    public static final String ADMIN_LIST = ADMIN_PREFIX + "list";
    public static final String ADMIN_TELEPORT = ADMIN_PREFIX + "teleport";
    public static final String ADMIN_RELOAD = ADMIN_PREFIX + "reload";
    public static final String ADMIN_STATUS = ADMIN_PREFIX + "status";

    public static final String BYPASS_REGION_PROTECTION =
            "sculpt.bypass.region-protection";

    private SculptPermissions() {
    }

    public static String resolution(final int size) {
        return RESOLUTION_PREFIX + size;
    }

    public static String fill(final String fill) {
        return FILL_PREFIX + fill;
    }

    public static String display(final String display) {
        return DISPLAY_PREFIX + display;
    }

    public static String editorTool(final String tool) {
        return EDITOR_PREFIX + tool;
    }

    public static String blueprint(final String operation) {
        return BLUEPRINT_PREFIX + operation;
    }

    public static String admin(final String operation) {
        return ADMIN_PREFIX + operation;
    }
}
