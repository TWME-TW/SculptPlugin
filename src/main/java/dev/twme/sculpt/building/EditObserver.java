package dev.twme.sculpt.building;

import java.util.List;

/**
 * Receives feedback about a running building operation. Both methods run on
 * the owning player's entity thread.
 */
public interface EditObserver {

    EditObserver NONE = new EditObserver() {};

    /** Called periodically while blocks are processed. */
    default void onProgress(final int processed, final int total) {
    }

    /**
     * Called once when the operation has finished or was cancelled.
     *
     * @param report  outcome counters
     * @param changed positions that were actually changed
     */
    default void onFinish(final EditReport report, final List<BlockPos> changed) {
    }
}
