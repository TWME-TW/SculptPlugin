package dev.twme.sculpt.editor.tool;

import dev.twme.sculpt.editor.CellTarget;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.VoxelBox;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.ui.EditorDialogs;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Cell-precise box selection: left click sets the first corner, left click
 * again sets the opposite corner. Right click opens the selection actions;
 * {@code Shift}+scroll grows or shrinks the selection by one cell.
 */
final class SelectTool implements Tool {

    private static final String PREFIX = "select.";
    private VoxelBox firstCorner;

    @Override
    public ToolId id() {
        return ToolId.SELECT;
    }

    @Override
    public void primary(final EditorSession session) {
        final CellTarget target = session.target();
        if (target == null) return;
        final VoxelBox cell = target.cell(false);
        if (firstCorner == null) {
            firstCorner = cell;
            session.flash("editor.select.first");
            return;
        }
        final VoxelBox selection = firstCorner.union(cell);
        firstCorner = null;
        session.setSelection(selection);
        session.flash("editor.select.done", VoxelBox.blocks(selection.sizeX()),
            VoxelBox.blocks(selection.sizeY()), VoxelBox.blocks(selection.sizeZ()));
    }

    @Override
    public void secondary(final EditorSession session) {
        if (session.selection() == null) {
            session.flash("editor.select.none");
            return;
        }
        EditorDialogs.selectionActions(session);
    }

    @Override
    public void openSettings(final EditorSession session) {
        secondary(session);
    }

    @Override
    public void adjust(final EditorSession session, final int delta) {
        final VoxelBox selection = session.selection();
        if (selection != null) session.setSelection(selection.expand((long) delta * session.cellSide()));
    }

    @Override
    public boolean cancel(final EditorSession session) {
        if (firstCorner != null) {
            firstCorner = null;
            return true;
        }
        if (session.selection() != null) {
            session.setSelection(null);
            session.flash("editor.select.cleared");
            return true;
        }
        return false;
    }

    @Override
    public void preview(final EditorSession session) {
        final CellTarget target = session.target();
        if (firstCorner != null && target != null) {
            final VoxelBox pending = firstCorner.union(target.cell(false));
            session.scene().outline(PREFIX + "pending", pending.min(), pending.max(), Colors.SELECT);
        } else {
            session.scene().remove(PREFIX + "pending");
        }
        Previews.cell(session, PREFIX + "cursor", false, Colors.CURSOR);
        showSelection(session);
    }

    /** The committed selection, shared with the transform tool. */
    static void showSelection(final EditorSession session) {
        final VoxelBox selection = session.selection();
        if (selection == null) {
            session.scene().remove("selection.outline");
            session.scene().remove("selection.faces");
            return;
        }
        session.scene().outline("selection.outline", selection.min(), selection.max(), Colors.SELECT);
        session.scene().faces("selection.faces", selection.min(), selection.max(),
            Colors.withAlpha(Colors.SELECT, 0x28));
    }

    @Override
    public void deactivate(final EditorSession session) {
        firstCorner = null;
        session.scene().removePrefix(PREFIX);
        session.scene().remove("selection.outline");
        session.scene().remove("selection.faces");
    }

    @Override
    public String status(final EditorSession session) {
        final VoxelBox selection = session.selection();
        return selection == null ? ""
            : MessageUtil.getTranslated(session.player(), "editor.status.selection",
                VoxelBox.blocks(selection.sizeX()), VoxelBox.blocks(selection.sizeY()),
                VoxelBox.blocks(selection.sizeZ()));
    }
}
