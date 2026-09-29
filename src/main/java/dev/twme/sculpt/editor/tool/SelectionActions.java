package dev.twme.sculpt.editor.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import dev.twme.sculpt.blueprint.BlueprintManager;
import dev.twme.sculpt.building.BlockCellEdit;
import dev.twme.sculpt.building.BuildLimits;
import dev.twme.sculpt.building.CellEdits;
import dev.twme.sculpt.building.CellVolume;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.RegionSelection;
import dev.twme.sculpt.editor.VoxelBox;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.plugin.FillConverter;
import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

/** Operations offered for the current selection. */
public final class SelectionActions {

    private SelectionActions() {
    }

    /** Remove every cell in the selection. */
    public static void delete(final EditorSession session) {
        cellEdit(session, "delete", BlockCellEdit.Operation.CARVE, null, Colors.REMOVE);
    }

    /** Fill the selection with the current material. */
    public static void fill(final EditorSession session) {
        final CellMaterial material = session.usableMaterial();
        if (material != null) {
            cellEdit(session, "fill", BlockCellEdit.Operation.ADD, material, Colors.ADD);
        }
    }

    /** Repaint the occupied cells of the selection with the current material. */
    public static void paint(final EditorSession session) {
        final CellMaterial material = session.usableMaterial();
        if (material != null) {
            cellEdit(session, "paint", BlockCellEdit.Operation.PAINT, material, Colors.PAINT);
        }
    }

    private static void cellEdit(
            final EditorSession session,
            final String label,
            final BlockCellEdit.Operation operation,
            final CellMaterial material,
            final int color) {
        final VoxelBox box = session.selection();
        if (box == null) {
            session.flash("editor.select.none");
            return;
        }
        final BuildLimits limits = session.service().engine().limits();
        session.commitAsync(label, () -> CellEdits.of(cells(box, limits), operation, material), color);
    }

    /**
     * Every cell of the box at the coarsest resolution whose cells align with
     * all six faces, keeping the edit as small as possible.
     */
    static CellVolume cells(final VoxelBox box, final BuildLimits limits) {
        int side = 16;
        while (side > 1 && !(aligned(box, side))) side /= 2;
        final int grid = 16 / side;
        final CellVolume volume = new CellVolume(grid, limits.maxBlocks(), limits.maxCells());
        for (long x = box.minX() / side; x < box.maxX() / side; x++) {
            for (long y = box.minY() / side; y < box.maxY() / side; y++) {
                for (long z = box.minZ() / side; z < box.maxZ() / side; z++) {
                    volume.add(x, y, z);
                }
            }
        }
        return volume;
    }

    private static boolean aligned(final VoxelBox box, final int side) {
        return Math.floorMod(box.minX(), side) == 0 && Math.floorMod(box.minY(), side) == 0
            && Math.floorMod(box.minZ(), side) == 0 && Math.floorMod(box.maxX(), side) == 0
            && Math.floorMod(box.maxY(), side) == 0 && Math.floorMod(box.maxZ(), side) == 0;
    }

    /** The blocks the selection touches. */
    public static RegionSelection blocks(final World world, final VoxelBox box) {
        return new RegionSelection(
            new Location(world, box.minBlockX(), box.minBlockY(), box.minBlockZ()),
            new Location(world, box.maxBlockX(), box.maxBlockY(), box.maxBlockZ()));
    }

    /** Replace block materials while keeping partial shapes such as stairs (not undoable). */
    public static void replaceVisual(final EditorSession session, final String blockData) {
        final VoxelBox box = session.selection();
        if (box == null) {
            session.flash("editor.select.none");
            return;
        }
        session.plugin().getReplaceCommand().execute(
            session.player(), blocks(session.player().getWorld(), box), blockData);
    }

    /** Restore automatic TextDisplay lighting in the selected blocks. */
    public static void relight(final EditorSession session) {
        final VoxelBox box = session.selection();
        if (box == null) {
            session.flash("editor.select.none");
            return;
        }
        session.plugin().getRelightCommand().execute(
            session.player(), blocks(session.player().getWorld(), box));
    }

    /** Change the collision strategy of every SculptBlock in the selection. */
    public static void convert(final EditorSession session, final FillMode fill) {
        final Player player = session.player();
        final VoxelBox box = session.selection();
        if (box == null) {
            session.flash("editor.select.none");
            return;
        }
        if (!player.hasPermission(SculptPermissions.CONVERT)) {
            MessageUtil.sendTranslated(player, "general.no_permission");
            MessageUtil.sendTranslated(player, "general.required_perm", SculptPermissions.CONVERT);
            return;
        }
        final FillConverter converter = session.plugin().getFillConverter();
        final RegionSelection region = blocks(player.getWorld(), box);
        final List<SculptBlock> targets = new ArrayList<>();
        for (final SculptBlock block : session.plugin().getActiveBlocks()) {
            if (block.world.equals(region.world()) && region.contains(block.pos)) targets.add(block);
        }
        if (targets.isEmpty() || converter == null) {
            MessageUtil.sendTranslated(player, "command.sculpt.convert.no_target");
            return;
        }
        final AtomicInteger remaining = new AtomicInteger(targets.size());
        final AtomicInteger changed = new AtomicInteger();
        final AtomicInteger protectedBlocks = new AtomicInteger();
        for (final SculptBlock sculpt : targets) {
            FoliaScheduler.runRegionTask(session.plugin(), sculpt.pos, () -> {
                try {
                    if (!session.plugin().canPlayerBuild(player, sculpt.pos.getBlock())) {
                        protectedBlocks.incrementAndGet();
                    } else if (converter.setFill(sculpt, fill)) {
                        changed.incrementAndGet();
                    }
                } catch (final RuntimeException failure) {
                    session.plugin().getLogger().log(Level.WARNING,
                        "[Sculpt] failed to convert a SculptBlock", failure);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        FoliaScheduler.runEntityTask(session.plugin(), player, () -> {
                            MessageUtil.sendTranslated(player, "command.sculpt.convert.changed",
                                changed.get(), fill.id());
                            if (protectedBlocks.get() > 0) {
                                MessageUtil.sendTranslated(player,
                                    "command.sculpt.convert.protected", protectedBlocks.get());
                            }
                        });
                    }
                }
            });
        }
    }

    /** Save the selected blocks as a blueprint. */
    public static void saveBlueprint(final EditorSession session, final String name, final boolean isPublic) {
        final Player player = session.player();
        final VoxelBox box = session.selection();
        if (box == null) {
            session.flash("editor.select.none");
            return;
        }
        if (!player.hasPermission(SculptPermissions.BLUEPRINT_SAVE)) {
            MessageUtil.sendTranslated(player, "general.no_permission");
            MessageUtil.sendTranslated(player, "general.required_perm", SculptPermissions.BLUEPRINT_SAVE);
            return;
        }
        final BlueprintManager blueprints = session.plugin().getBlueprintManager();
        if (blueprints == null || !blueprints.isEnabled()) {
            MessageUtil.sendTranslated(player, "command.sculpt.blueprint.disabled");
            return;
        }
        final RegionSelection region = blocks(player.getWorld(), box);
        blueprints.clearSelection(player);
        if (blueprints.getSelectionMode(player) != BlueprintManager.SelectionMode.CUBOID) {
            blueprints.toggleSelectionMode(player);
        }
        blueprints.selectFirstCorner(player, region.pos1(), null);
        final BlueprintManager.SelectionResult result = blueprints.selectSecondCorner(player, region.pos2());
        if (result != BlueprintManager.SelectionResult.CUBOID_SELECTED) {
            MessageUtil.sendTranslated(player, result == BlueprintManager.SelectionResult.SELECTION_TOO_LARGE
                ? "command.sculpt.blueprint.select.too_large" : "editor.select.blueprint_failed");
            return;
        }
        final String error = blueprints.saveBlueprint(player, name, isPublic, null);
        if (error != null) {
            MessageUtil.sendTranslated(player, error, name);
        } else {
            MessageUtil.sendTranslated(player, "command.sculpt.blueprint.save.success", name);
        }
    }
}
