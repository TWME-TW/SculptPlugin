package dev.twme.sculpt.editor.tool;

import dev.twme.sculpt.building.BlockCellEdit;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.editor.CellTarget;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.preview.Colors;

/** Repaint occupied cells (right) or pick the material under the cursor (left). */
final class PaintTool extends BrushTool {

    /** Painting defaults to a single cell the first time the tool is used. */
    private boolean initialized;

    @Override
    public ToolId id() {
        return ToolId.PAINT;
    }

    @Override
    public void activate(final EditorSession session) {
        if (!initialized) {
            configure(session, 0, shape());
            initialized = true;
        }
    }

    @Override
    public void primary(final EditorSession session) {
        session.pickMaterialAtCursor();
    }

    @Override
    public void secondary(final EditorSession session) {
        final CellTarget target = session.target();
        if (target == null) return;
        final CellMaterial material = session.usableMaterial();
        if (material == null) return;
        stroke(session, target.x(), target.y(), target.z(), BlockCellEdit.Operation.PAINT, material,
            Colors.PAINT);
    }

    @Override
    protected int previewColor() {
        return Colors.PAINT;
    }
}
