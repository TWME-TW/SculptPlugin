package dev.twme.sculpt.editor.tool;

import dev.twme.sculpt.building.BlockCellEdit;
import dev.twme.sculpt.building.CellEdits;
import dev.twme.sculpt.building.CellVolume;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.editor.CellTarget;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.preview.Colors;

/** Remove one cell with left click, place one with right click. */
final class SculptTool implements Tool {

    private static final String CURSOR = "sculpt.cursor";

    @Override
    public ToolId id() {
        return ToolId.SCULPT;
    }

    @Override
    public void primary(final EditorSession session) {
        final CellTarget target = session.target();
        if (target == null) return;
        final CellVolume cell = new CellVolume(target.grid(), 1, 1);
        cell.add(target.x(), target.y(), target.z());
        session.commit("sculpt", CellEdits.of(cell, BlockCellEdit.Operation.CARVE, null), Colors.REMOVE);
    }

    @Override
    public void secondary(final EditorSession session) {
        final CellTarget target = session.target();
        if (target == null) return;
        final CellMaterial material = session.usableMaterial();
        if (material == null) return;
        final CellVolume cell = new CellVolume(target.grid(), 1, 1);
        cell.add(target.adjacentX(), target.adjacentY(), target.adjacentZ());
        session.commit("sculpt", CellEdits.of(cell, BlockCellEdit.Operation.ADD, material), Colors.ADD);
    }

    @Override
    public void preview(final EditorSession session) {
        Previews.cell(session, CURSOR, false, Colors.CURSOR);
    }
}
