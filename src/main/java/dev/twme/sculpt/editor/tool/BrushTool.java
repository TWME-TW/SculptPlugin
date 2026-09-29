package dev.twme.sculpt.editor.tool;

import java.util.Locale;

import dev.twme.sculpt.building.BlockCellEdit;
import dev.twme.sculpt.building.BuildLimits;
import dev.twme.sculpt.building.CellEdits;
import dev.twme.sculpt.building.CellVolume;
import dev.twme.sculpt.building.ShapeRasterizer;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.editor.CellTarget;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.ui.EditorDialogs;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Carve (left) or add (right) a sphere or cube of cells. The footprint is
 * previewed around the cursor; {@code Shift}+scroll changes its radius.
 */
public class BrushTool implements Tool {

    private int radius = 1;
    private ShapeRasterizer.BrushShape shape = ShapeRasterizer.BrushShape.SPHERE;

    @Override
    public ToolId id() {
        return ToolId.BRUSH;
    }

    public int radius() {
        return radius;
    }

    public ShapeRasterizer.BrushShape shape() {
        return shape;
    }

    public void configure(final EditorSession session, final int newRadius,
                          final ShapeRasterizer.BrushShape newShape) {
        this.radius = Math.clamp(newRadius, 0, limits(session).maxBrushRadius());
        this.shape = newShape;
    }

    @Override
    public void primary(final EditorSession session) {
        final CellTarget target = session.target();
        if (target == null) return;
        stroke(session, target.x(), target.y(), target.z(), BlockCellEdit.Operation.CARVE, null,
            Colors.REMOVE);
    }

    @Override
    public void secondary(final EditorSession session) {
        final CellTarget target = session.target();
        if (target == null) return;
        final CellMaterial material = session.usableMaterial();
        if (material == null) return;
        stroke(session, target.adjacentX(), target.adjacentY(), target.adjacentZ(),
            BlockCellEdit.Operation.ADD, material, Colors.ADD);
    }

    protected final void stroke(
            final EditorSession session,
            final long x,
            final long y,
            final long z,
            final BlockCellEdit.Operation operation,
            final CellMaterial material,
            final int color) {
        final int grid = session.grid();
        final BuildLimits limits = limits(session);
        final int brushRadius = radius;
        final ShapeRasterizer.BrushShape brushShape = shape;
        session.commitAsync(id().id(), () -> {
            final CellVolume volume = new CellVolume(grid, limits.maxBlocks(), limits.maxCells());
            ShapeRasterizer.brush(x, y, z, brushRadius, brushShape, volume);
            return CellEdits.of(volume, operation, material);
        }, color);
    }

    @Override
    public void adjust(final EditorSession session, final int delta) {
        radius = Math.clamp(radius + delta, 0, limits(session).maxBrushRadius());
    }

    @Override
    public void openSettings(final EditorSession session) {
        EditorDialogs.brush(session, this);
    }

    @Override
    public void preview(final EditorSession session) {
        final CellTarget target = session.target();
        if (target == null) {
            session.scene().removePrefix(id().id() + ".");
            return;
        }
        Previews.brush(session, id().id() + ".brush", target.cell(false), radius, shape, previewColor());
    }

    protected int previewColor() {
        return Colors.CURSOR;
    }

    @Override
    public String status(final EditorSession session) {
        return MessageUtil.getTranslated(session.player(), "editor.status.brush",
            radius, MessageUtil.getTranslated(session.player(),
                "editor.brush_shape." + shape.name().toLowerCase(Locale.ROOT)));
    }

    protected static BuildLimits limits(final EditorSession session) {
        return session.service().engine().limits();
    }
}
