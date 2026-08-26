package dev.twme.sculpt.plugin;

import org.bukkit.Location;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptBlock;

/** Applies physical fill strategies to existing SculptBlocks. */
public final class FillConverter {

    private final Sculpt plugin;

    public FillConverter(final Sculpt plugin) {
        this.plugin = plugin;
    }

    /**
     * Apply one fill strategy without changing shape, material, or renderer.
     *
     * @return whether the block changed
     */
    public boolean setFill(final SculptBlock block, final FillMode mode) {
        if (block == null || block.state != SculptBlock.State.SCULPTED) {
            return false;
        }
        if (!block.setFillMode(mode)) return false;
        block.syncPDC();
        return true;
    }

    /** Apply a fill strategy to the SculptBlock at one exact location. */
    public int setSingle(final Location location, final FillMode mode) {
        final SculptBlock block = plugin.getActiveBlock(BlockPosKey.of(location));
        return setFill(block, mode) ? 1 : 0;
    }
}
