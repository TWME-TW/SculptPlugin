package dev.twme.sculpt.building;

import java.util.Arrays;
import java.util.Objects;

import org.bukkit.block.data.BlockData;
import org.joml.Quaternionf;

import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.core.SculptDisplayMode;

/**
 * Detached state of one world position, recorded before and after a
 * building or sculpting edit so the edit can be undone and redone.
 */
public final class BlockSnapshot {

    public enum Kind { REGULAR, SCULPT }

    private final Kind kind;
    private final BlockData worldData;
    private final SculptState sculpt;

    private BlockSnapshot(final Kind kind, final BlockData worldData, final SculptState sculpt) {
        this.kind = kind;
        this.worldData = worldData;
        this.sculpt = sculpt;
    }

    /** A vanilla block (including air) without an active SculptBlock. */
    public static BlockSnapshot regular(final BlockData worldData) {
        return new BlockSnapshot(Kind.REGULAR,
            Objects.requireNonNull(worldData, "worldData").clone(), null);
    }

    /** A detached copy of an active SculptBlock. */
    public static BlockSnapshot sculpt(final BlockData worldData, final SculptBlock block) {
        final OctreeNode tree = OctreeCellEditor.copy(block.root, block.originalBlockData);
        return new BlockSnapshot(Kind.SCULPT, worldData.clone(), new SculptState(
            block.originalBlockData.clone(),
            block.matchedVariantKey == null ? "" : block.matchedVariantKey,
            new Quaternionf(block.blockRotation),
            block.tintArgb,
            tree,
            block.isMixed(),
            block.fillMode(),
            block.displayMode(),
            block.root.serialize()));
    }

    public Kind kind() {
        return kind;
    }

    public BlockData worldData() {
        return worldData.clone();
    }

    public SculptState sculptState() {
        return sculpt;
    }

    /** Whether the live state still equals this snapshot. */
    public boolean matches(final BlockData currentWorldData, final SculptBlock currentSculpt) {
        if (kind == Kind.REGULAR) {
            return currentSculpt == null
                && worldData.getAsString().equals(currentWorldData.getAsString());
        }
        return currentSculpt != null
            && sculpt.originalBlockData().equals(currentSculpt.originalBlockData)
            && Arrays.equals(sculpt.fingerprint(), currentSculpt.root.serialize());
    }

    /** Whether both snapshots describe the same visible state. */
    public boolean sameStateAs(final BlockSnapshot other) {
        if (other == null || kind != other.kind) return false;
        if (kind == Kind.REGULAR) {
            return worldData.getAsString().equals(other.worldData.getAsString());
        }
        return sculpt.originalBlockData().equals(other.sculpt.originalBlockData())
            && Arrays.equals(sculpt.fingerprint(), other.sculpt.fingerprint());
    }

    /** Material used for feedback sounds. */
    public BlockData representativeMaterial() {
        if (kind == Kind.REGULAR) return worldData;
        final BlockData dominant = OctreeCellEditor.dominantMaterial(sculpt.tree());
        return dominant == null ? sculpt.originalBlockData() : dominant;
    }

    /** Leaf count used to budget how much restoration work runs per tick. */
    public int workUnits() {
        return kind == Kind.REGULAR ? 1
            : Math.max(1, OctreeCellEditor.occupiedLeaves(sculpt.tree()));
    }

    /**
     * Everything needed to rebuild an equivalent SculptBlock. The tree is
     * private to the snapshot; callers must copy it before installing it.
     */
    public record SculptState(
        BlockData originalBlockData,
        String matchedVariantKey,
        Quaternionf rotation,
        int tintArgb,
        OctreeNode tree,
        boolean mixed,
        FillMode fillMode,
        SculptDisplayMode displayMode,
        byte[] fingerprint
    ) {}
}
