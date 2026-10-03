package dev.twme.sculpt.editor.tool;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3d;

import dev.twme.sculpt.building.BlockCellEdit;
import dev.twme.sculpt.building.BlockPos;
import dev.twme.sculpt.building.CellSamples;
import dev.twme.sculpt.building.CellVolume;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.VoxelBox;
import dev.twme.sculpt.editor.VoxelTransform;
import dev.twme.sculpt.editor.gizmo.Gizmo;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.ui.EditorDialogs;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Move, copy, rotate, and mirror the selection with draggable gizmo handles.
 *
 * <p>The gizmo sits at the center of the selection. Left click grabs the
 * hovered handle and it follows the view until left click releases it; only
 * movement along that handle counts, so a drag stays on its axis.
 * {@code Shift} freezes an active drag, {@code Q} discards it, and right
 * click applies the result.</p>
 *
 * <p>Rotation is free-form: the ring handle produces any angle, snapped to a
 * small step unless snapping is off. Quarter turns stay exact because the
 * rotation core evaluates them with integer arithmetic.</p>
 */
public final class TransformTool implements Tool {

    private static final String PREFIX = "transform.";
    private static final String GIZMO_PREFIX = PREFIX + "gizmo.";

    private final Gizmo gizmo = new Gizmo(GIZMO_PREFIX);
    private boolean copy;
    private boolean grabbed;

    @Override
    public ToolId id() {
        return ToolId.TRANSFORM;
    }

    public boolean copy() {
        return copy;
    }

    public void configure(final boolean newCopy) {
        this.copy = newCopy;
    }

    /** The pending rotation, or {@code null} without a selection. */
    public VoxelTransform transform(final EditorSession session) {
        final VoxelBox selection = session.selection();
        if (selection == null) return null;
        return new VoxelTransform(gizmo.rotation(selection),
            gizmo.offsetX(), gizmo.offsetY(), gizmo.offsetZ());
    }

    /** The pivot the handles are drawn around: the center of the selection. */
    private static Vector3d pivot(final VoxelBox selection) {
        return selection.center();
    }

    /** The player's eye position, the origin of every pick ray. */
    static Vector3d eye(final Player player) {
        final Location eye = player.getEyeLocation();
        return new Vector3d(eye.getX(), eye.getY(), eye.getZ());
    }

    /** The player's normalized look direction. */
    static Vector3d view(final Player player) {
        final org.bukkit.util.Vector direction = player.getEyeLocation().getDirection();
        return new Vector3d(direction.getX(), direction.getY(), direction.getZ()).normalize();
    }

    // =====================================================================
    //  Input
    // =====================================================================

    @Override
    public void primary(final EditorSession session) {
        final VoxelBox selection = session.selection();
        if (selection == null) {
            session.flash("editor.select.none");
            return;
        }
        if (grabbed) {
            // Releasing a mirror handle toggles it; the others just stop.
            if (gizmo.dragging() != null && gizmo.dragging().isMirror()) gizmo.toggleMirror();
            gizmo.release();
            grabbed = false;
            session.flash("editor.transform.dropped");
            return;
        }
        if (gizmo.beginDrag(pivot(selection), eye(session.player()), view(session.player()))) {
            grabbed = true;
            session.flash("editor.transform.grabbed", gizmo.hovered().id());
        }
    }

    @Override
    public void secondary(final EditorSession session) {
        apply(session);
    }

    /** {@code Shift}+scroll cycles the rotation snap step. */
    @Override
    public void adjust(final EditorSession session, final int delta) {
        if (delta == 0) return;
        gizmo.adjustAngleStep(delta);
        final double step = gizmo.angleStep();
        session.flash("editor.transform.snap",
            step <= 0 ? MessageUtil.getTranslated(session.player(), "editor.transform.snap_free")
                : Math.round(step) + "°");
    }

    @Override
    public boolean cancel(final EditorSession session) {
        if (!grabbed && !gizmo.isChanged()) return false;
        gizmo.reset();
        grabbed = false;
        session.flash("editor.transform.reset_done");
        return true;
    }

    @Override
    public void openSettings(final EditorSession session) {
        EditorDialogs.transform(session, this);
    }

    @Override
    public void deactivate(final EditorSession session) {
        grabbed = false;
        gizmo.reset();
        gizmo.clear(session.scene());
        session.scene().removePrefix(PREFIX);
        session.scene().remove("selection.outline");
        session.scene().remove("selection.faces");
    }

    // =====================================================================
    //  Preview
    // =====================================================================

    @Override
    public void preview(final EditorSession session) {
        SelectTool.showSelection(session);
        final VoxelBox selection = session.selection();
        if (selection == null) {
            gizmo.clearHover();
            gizmo.clear(session.scene());
            session.scene().remove(PREFIX + "ghost");
            session.scene().remove(PREFIX + "ghost.faces");
            return;
        }
        final Vector3d center = pivot(selection);
        // A frozen drag (sneaking) keeps the handles still, like DEU.
        if (!session.player().isSneaking()) {
            gizmo.update(center, eye(session.player()), view(session.player()));
        }
        gizmo.preview(session.scene(), center);

        final VoxelTransform transform = transform(session);
        if (transform.isIdentity()) {
            session.scene().remove(PREFIX + "ghost");
            session.scene().remove(PREFIX + "ghost.faces");
            return;
        }
        final VoxelBox destination = transform.destination(selection);
        session.scene().outline(PREFIX + "ghost", destination.min(), destination.max(), Colors.TRANSFORM);
        session.scene().faces(PREFIX + "ghost.faces", destination.min(), destination.max(),
            Colors.withAlpha(Colors.TRANSFORM, 0x30));
    }

    // =====================================================================
    //  Apply
    // =====================================================================

    /** Move or copy the selected voxels to the ghost position. */
    public void apply(final EditorSession session) {
        final VoxelBox selection = session.selection();
        if (selection == null) {
            session.flash("editor.select.none");
            return;
        }
        final VoxelTransform transform = transform(session);
        if (transform.isIdentity()) {
            session.flash("editor.transform.unchanged");
            return;
        }
        final long limit = session.service().engine().limits().maxTransformVoxels();
        if (selection.volume() > limit) {
            MessageUtil.sendTranslated(session.player(), "building.result.too_many_cells", limit);
            return;
        }
        if (!session.begin()) return;
        grabbed = false;
        gizmo.release();
        final Player player = session.player();
        final World world = player.getWorld();
        final VoxelBox source = selection;
        final VoxelBox destination = transform.destination(source);
        final Set<BlockPos> blocks = blocks(source.union(destination));
        final boolean move = !copy;
        session.service().engine().sample(player, world, blocks, 16, samples -> {
            final Map<BlockPos, BlockCellEdit> edits = edits(transform, source, samples, move);
            session.service().engine().release(player);
            FoliaScheduler.runEntityTask(session.plugin(), player, () -> {
                session.commit(move ? "move" : "copy", edits, Colors.TRANSFORM);
                session.setSelection(destination);
                gizmo.reset();
            });
        });
    }

    /** Every block a box touches. */
    private static Set<BlockPos> blocks(final VoxelBox box) {
        final Set<BlockPos> blocks = new LinkedHashSet<>();
        for (int x = box.minBlockX(); x <= box.maxBlockX(); x++) {
            for (int y = box.minBlockY(); y <= box.maxBlockY(); y++) {
                for (int z = box.minBlockZ(); z <= box.maxBlockZ(); z++) {
                    blocks.add(new BlockPos(x, y, z));
                }
            }
        }
        return blocks;
    }

    /**
     * Voxel edits for a transform.
     *
     * <p>When the transform only translates (or translates and mirrors), each
     * source voxel is written to exactly one destination voxel, which is
     * lossless. When it rotates by an angle that is not a whole quarter turn,
     * the destination is instead <em>resampled</em>: every destination voxel
     * looks up the source voxel that lands in it, so the result has no holes
     * and no duplicate writes. Quarter turns take the exact path.</p>
     *
     * <p>Occupied voxels whose material is unknown (partial vanilla blocks)
     * are left where they are.</p>
     */
    static Map<BlockPos, BlockCellEdit> edits(
            final VoxelTransform transform,
            final VoxelBox source,
            final CellSamples samples,
            final boolean move) {
        final Map<BlockPos, BitSet> carve = new HashMap<>();
        final Map<BlockPos, Map<CellMaterial, BitSet>> add = new HashMap<>();
        if (!transform.rotation().isIdentity() && !transform.rotation().isQuarterTurn()) {
            resample(transform, source, samples, move, carve, add);
        } else {
            forward(transform, source, samples, move, carve, add);
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

    /** Write each source voxel to its destination; exact for translation and quarter turns. */
    private static void forward(
            final VoxelTransform transform,
            final VoxelBox source,
            final CellSamples samples,
            final boolean move,
            final Map<BlockPos, BitSet> carve,
            final Map<BlockPos, Map<CellMaterial, BitSet>> add) {
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
                        markMaterial(add, target[0], target[1], target[2], material);
                    }
                }
            }
        }
    }

    /** Look up the source of every destination voxel, so nothing is left empty. */
    private static void resample(
            final VoxelTransform transform,
            final VoxelBox source,
            final CellSamples samples,
            final boolean move,
            final Map<BlockPos, BitSet> carve,
            final Map<BlockPos, Map<CellMaterial, BitSet>> add) {
        final VoxelBox destination = transform.destination(source);
        for (long x = destination.minX(); x < destination.maxX(); x++) {
            for (long y = destination.minY(); y < destination.maxY(); y++) {
                for (long z = destination.minZ(); z < destination.maxZ(); z++) {
                    final long[] from = transform.inverse(x, y, z, source);
                    if (from == null) {
                        // Outside the rotated footprint: clear whatever was there.
                        mark(carve, x, y, z);
                        continue;
                    }
                    final CellMaterial material = samples.cellMaterial(from[0], from[1], from[2]);
                    final boolean occupied = samples.occupied(from[0], from[1], from[2]);
                    if (occupied && material == null) continue;
                    if (material == null) mark(carve, x, y, z);
                    else markMaterial(add, x, y, z, material);
                }
            }
        }
        if (move) {
            for (long x = source.minX(); x < source.maxX(); x++) {
                for (long y = source.minY(); y < source.maxY(); y++) {
                    for (long z = source.minZ(); z < source.maxZ(); z++) {
                        if (samples.occupied(x, y, z)) mark(carve, x, y, z);
                    }
                }
            }
        }
    }

    private static void mark(final Map<BlockPos, BitSet> bits, final long x, final long y, final long z) {
        bits.computeIfAbsent(block(x, y, z), ignored -> new BitSet()).set(index(x, y, z));
    }

    private static void markMaterial(final Map<BlockPos, Map<CellMaterial, BitSet>> add,
                                     final long x, final long y, final long z,
                                     final CellMaterial material) {
        add.computeIfAbsent(block(x, y, z), ignored -> new HashMap<>())
            .computeIfAbsent(material, ignored -> new BitSet())
            .set(index(x, y, z));
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
        final VoxelBox selection = session.selection();
        if (selection == null) return "";
        final String label = copy ? "editor.status.copy" : "editor.status.move";
        final long degrees = Math.round(Math.toDegrees(gizmo.angle()));
        final String handle = gizmo.dragging() != null ? gizmo.dragging().id()
            : gizmo.hovered() != null ? gizmo.hovered().id() : "";
        if (degrees == 0) {
            return MessageUtil.getTranslated(session.player(), label,
                VoxelBox.blocks(gizmo.offsetX()), VoxelBox.blocks(gizmo.offsetY()),
                VoxelBox.blocks(gizmo.offsetZ()), 0);
        }
        return MessageUtil.getTranslated(session.player(), label,
            VoxelBox.blocks(gizmo.offsetX()), VoxelBox.blocks(gizmo.offsetY()),
            VoxelBox.blocks(gizmo.offsetZ()), degrees)
            + (handle.isEmpty() ? "" : " · " + handle);
    }
}
