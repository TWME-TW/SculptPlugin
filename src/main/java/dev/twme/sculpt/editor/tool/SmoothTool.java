package dev.twme.sculpt.editor.tool;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.World;

import dev.twme.sculpt.building.BlockCellEdit;
import dev.twme.sculpt.building.BlockPos;
import dev.twme.sculpt.building.BrushSmoother;
import dev.twme.sculpt.building.BuildLimits;
import dev.twme.sculpt.building.CellVolume;
import dev.twme.sculpt.building.ShapeRasterizer;
import dev.twme.sculpt.editor.CellTarget;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.ui.EditorDialogs;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Round off spikes and fill pits inside the brush footprint (either click).
 * {@code Shift}+scroll changes the radius; the settings dialog also sets how
 * many smoothing passes run, so a rough surface can be smoothed further.
 */
public final class SmoothTool extends BrushTool {

    private int passes;
    private boolean configured;

    @Override
    public ToolId id() {
        return ToolId.SMOOTH;
    }

    /** Number of smoothing passes; one means a single rounding step. */
    public int passes() {
        return passes;
    }

    @Override
    public void activate(final EditorSession session) {
        if (configured) return;
        configured = true;
        passes = Math.clamp(limits(session).smoothPasses(), 1, limits(session).maxSmoothPasses());
    }

    public void configure(final EditorSession session, final int newRadius,
                          final ShapeRasterizer.BrushShape newShape, final int newPasses) {
        super.configure(session, newRadius, newShape);
        this.passes = Math.clamp(newPasses, 1, limits(session).maxSmoothPasses());
        this.configured = true;
    }

    @Override
    public void primary(final EditorSession session) {
        smooth(session);
    }

    @Override
    public void secondary(final EditorSession session) {
        smooth(session);
    }

    private void smooth(final EditorSession session) {
        final CellTarget target = session.target();
        if (target == null) return;
        final BuildLimits limits = limits(session);
        final int passes = this.passes;
        final CellVolume area = new CellVolume(target.grid(), limits.maxBlocks(), limits.maxCells());
        try {
            ShapeRasterizer.brush(target.x(), target.y(), target.z(), radius(), shape(), area);
        } catch (final CellVolume.LimitExceededException tooLarge) {
            session.flash("building.result.too_many_cells", tooLarge.limit());
            return;
        }
        if (!session.begin()) return;
        final Set<BlockPos> sampled = new HashSet<>();
        for (final BlockPos block : area.blocks().keySet()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        sampled.add(new BlockPos(block.x() + dx, block.y() + dy, block.z() + dz));
                    }
                }
            }
        }
        final World world = session.player().getWorld();
        session.service().engine().sample(session.player(), world, sampled, target.grid(), samples -> {
            final Map<BlockPos, BlockCellEdit> edits = BrushSmoother.smooth(area, samples, passes);
            session.service().engine().release(session.player());
            FoliaScheduler.runEntityTask(session.plugin(), session.player(),
                () -> session.commit("smooth", edits, Colors.PAINT));
        });
    }

    @Override
    public void openSettings(final EditorSession session) {
        EditorDialogs.smooth(session, this);
    }

    @Override
    public String status(final EditorSession session) {
        return MessageUtil.getTranslated(session.player(), "editor.status.smooth",
            radius(), MessageUtil.getTranslated(session.player(),
                "editor.brush_shape." + shape().name().toLowerCase(Locale.ROOT)), passes);
    }

    @Override
    protected int previewColor() {
        return Colors.PAINT;
    }
}
