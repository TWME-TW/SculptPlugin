package dev.twme.sculpt.editor.tool;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.entity.Player;

import dev.twme.sculpt.building.BlockCellEdit;
import dev.twme.sculpt.building.BlockPos;
import dev.twme.sculpt.building.CellSamples;
import dev.twme.sculpt.building.CellVolume;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.editor.CellTarget;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.VoxelBox;
import dev.twme.sculpt.editor.VoxelTransform;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.ui.EditorDialogs;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Move, copy, rotate, and mirror the selection with voxel precision. Left
 * click grabs the selection so a ghost follows the cursor; left click again
 * drops it. {@code Shift}+scroll rotates by 90°, right click applies.
 */
public final class TransformTool implements Tool {

    private static final String PREFIX = "transform.";

    private boolean grabbed;
    private long anchorX;
    private long anchorY;
    private long anchorZ;
    private long offsetX;
    private long offsetY;
    private long offsetZ;
    private int quarterTurns;
    private boolean mirrorX;
    private boolean mirrorZ;
    private boolean copy;

    @Override
    public ToolId id() {
        return ToolId.TRANSFORM;
    }

    public boolean copy() {
        return copy;
    }

    public boolean mirrorX() {
        return mirrorX;
    }

    public boolean mirrorZ() {
        return mirrorZ;
    }

    public int quarterTurns() {
        return quarterTurns;
    }

    public void configure(final boolean newCopy, final boolean newMirrorX, final boolean newMirrorZ,
                          final int newQuarterTurns) {
        this.copy = newCopy;
        this.mirrorX = newMirrorX;
        this.mirrorZ = newMirrorZ;
        this.quarterTurns = Math.floorMod(newQuarterTurns, 4);
    }

    @Override
    public void primary(final EditorSession session) {
        if (session.selection() == null) {
            session.flash("editor.select.none");
            return;
        }
        if (grabbed) {
            grabbed = false;
            session.flash("editor.transform.dropped");
            return;
        }
        final CellTarget target = session.target();
        if (target == null) return;
        final VoxelBox cell = target.cell(false);
        anchorX = cell.minX() - offsetX;
        anchorY = cell.minY() - offsetY;
        anchorZ = cell.minZ() - offsetZ;
        grabbed = true;
        session.flash("editor.transform.grabbed");
    }

    @Override
    public void secondary(final EditorSession session) {
        apply(session);
    }

    @Override
    public void adjust(final EditorSession session, final int delta) {
        quarterTurns = Math.floorMod(quarterTurns + delta, 4);
    }

    @Override
    public boolean cancel(final EditorSession session) {
        final VoxelTransform transform = transform(session);
        if (transform == null || transform.isIdentity()) {
            if (!grabbed) return false;
            grabbed = false;
            return true;
        }
        reset();
        session.flash("editor.transform.reset");
        return true;
    }

    public void reset() {
        grabbed = false;
        offsetX = offsetY = offsetZ = 0;
        quarterTurns = 0;
        mirrorX = mirrorZ = false;
    }

    @Override
    public void openSettings(final EditorSession session) {
        EditorDialogs.transform(session, this);
    }

    @Override
    public void deactivate(final EditorSession session) {
        grabbed = false;
        session.scene().removePrefix(PREFIX);
        session.scene().remove("selection.outline");
        session.scene().remove("selection.faces");
    }

    @Override
    public void preview(final EditorSession session) {
        SelectTool.showSelection(session);
        final VoxelBox selection = session.selection();
        final CellTarget target = session.target();
        if (grabbed && target != null) {
            final VoxelBox cell = target.cell(false);
            offsetX = cell.minX() - anchorX;
            offsetY = cell.minY() - anchorY;
            offsetZ = cell.minZ() - anchorZ;
        }
        final VoxelTransform transform = transform(session);
        if (selection == null || transform == null || transform.isIdentity()) {
            session.scene().removePrefix(PREFIX);
            return;
        }
        final VoxelBox destination = transform.destination();
        session.scene().outline(PREFIX + "ghost", destination.min(), destination.max(), Colors.TRANSFORM);
        session.scene().faces(PREFIX + "ghost.faces", destination.min(), destination.max(),
            Colors.withAlpha(Colors.TRANSFORM, 0x30));
    }

    VoxelTransform transform(final EditorSession session) {
        final VoxelBox selection = session.selection();
        return selection == null ? null
            : new VoxelTransform(selection, quarterTurns, mirrorX, mirrorZ, offsetX, offsetY, offsetZ);
    }

    /** Move or copy the selected voxels to the ghost position. */
    public void apply(final EditorSession session) {
        final VoxelTransform transform = transform(session);
        if (transform == null) {
            session.flash("editor.select.none");
            return;
        }
        if (transform.isIdentity()) {
            session.flash("editor.transform.unchanged");
            return;
        }
        final long limit = session.service().engine().limits().maxTransformVoxels();
        if (transform.source().volume() > limit) {
            MessageUtil.sendTranslated(session.player(), "building.result.too_many_cells", limit);
            return;
        }
        if (!session.begin()) return;
        grabbed = false;
        final Player player = session.player();
        final World world = player.getWorld();
        final VoxelBox source = transform.source();
        final Set<BlockPos> blocks = new LinkedHashSet<>();
        for (int x = source.minBlockX(); x <= source.maxBlockX(); x++) {
            for (int y = source.minBlockY(); y <= source.maxBlockY(); y++) {
                for (int z = source.minBlockZ(); z <= source.maxBlockZ(); z++) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
        }
        final boolean move = !copy;
        session.service().engine().sample(player, world, blocks, 16, samples -> {
            final Map<BlockPos, BlockCellEdit> edits = edits(transform, samples, move);
            session.service().engine().release(player);
            FoliaScheduler.runEntityTask(session.plugin(), player, () -> {
                session.commit(move ? "move" : "copy", edits, Colors.TRANSFORM);
                session.setSelection(transform.destination());
                reset();
            });
        });
    }

    /**
     * Voxel edits for a transform: the source is carved (when moving), then
     * the destination receives every source voxel, empty ones included.
     * Occupied voxels whose material is unknown (partial vanilla blocks) are
     * left where they are.
     */
    static Map<BlockPos, BlockCellEdit> edits(
            final VoxelTransform transform,
            final CellSamples samples,
            final boolean move) {
        final Map<BlockPos, BitSet> carve = new HashMap<>();
        final Map<BlockPos, Map<CellMaterial, BitSet>> add = new HashMap<>();
        final VoxelBox source = transform.source();
        for (long x = source.minX(); x < source.maxX(); x++) {
            for (long y = source.minY(); y < source.maxY(); y++) {
                for (long z = source.minZ(); z < source.maxZ(); z++) {
                    final boolean occupied = samples.occupied(x, y, z);
                    final CellMaterial material = samples.cellMaterial(x, y, z);
                    if (occupied && material == null) continue;
                    if (move && occupied) mark(carve, x, y, z);
                    final long[] target = transform.apply(x, y, z);
                    if (material == null) {
                        mark(carve, target[0], target[1], target[2]);
                    } else {
                        final BlockPos block = block(target[0], target[1], target[2]);
                        add.computeIfAbsent(block, ignored -> new HashMap<>())
                            .computeIfAbsent(material, ignored -> new BitSet())
                            .set(index(target[0], target[1], target[2]));
                    }
                }
            }
        }
        // A destination voxel that also receives material must not be carved
        // afterwards; layers apply in order, so carving goes first.
        final Map<BlockPos, BlockCellEdit> edits = new HashMap<>();
        final Set<BlockPos> positions = new LinkedHashSet<>(carve.keySet());
        positions.addAll(add.keySet());
        for (final BlockPos position : positions) {
            final List<BlockCellEdit.Layer> layers = new ArrayList<>();
            final BitSet carved = carve.get(position);
            if (carved != null) {
                layers.add(new BlockCellEdit.Layer(BlockCellEdit.Operation.CARVE, null, carved));
            }
            final Map<CellMaterial, BitSet> materials = add.get(position);
            if (materials != null) {
                materials.forEach((material, bits) -> layers.add(new BlockCellEdit.Layer(
                    BlockCellEdit.Operation.ADD, material.blockData(), material.playerHeadTexture(), bits)));
            }
            edits.put(position, new BlockCellEdit(16, layers));
        }
        return edits;
    }

    private static void mark(final Map<BlockPos, BitSet> bits, final long x, final long y, final long z) {
        bits.computeIfAbsent(block(x, y, z), ignored -> new BitSet()).set(index(x, y, z));
    }

    private static BlockPos block(final long x, final long y, final long z) {
        return new BlockPos((int) Math.floorDiv(x, 16), (int) Math.floorDiv(y, 16), (int) Math.floorDiv(z, 16));
    }

    private static int index(final long x, final long y, final long z) {
        return CellVolume.localIndex(16,
            (int) Math.floorMod(x, 16), (int) Math.floorMod(y, 16), (int) Math.floorMod(z, 16));
    }

    @Override
    public String status(final EditorSession session) {
        final VoxelTransform transform = transform(session);
        if (transform == null) return "";
        return MessageUtil.getTranslated(session.player(), copy ? "editor.status.copy" : "editor.status.move",
            VoxelBox.blocks(offsetX), VoxelBox.blocks(offsetY), VoxelBox.blocks(offsetZ), quarterTurns * 90);
    }
}
