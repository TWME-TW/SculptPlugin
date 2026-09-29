package dev.twme.sculpt.building;

/** Outcome counters of one building, undo, or redo operation. */
public final class EditReport {
    public int changed;
    public int unchanged;
    public int protectedBlocks;
    public int obstructed;
    public int locked;
    public int limitReached;
    public int stale;
    public int failed;
    /** Undo/redo only: history entries processed. */
    public int entries;
    public boolean historyRecorded = true;

    void count(final BuildWorldWriter.Status status) {
        switch (status) {
            case CHANGED -> changed++;
            case UNCHANGED -> unchanged++;
            case PROTECTED -> protectedBlocks++;
            case OBSTRUCTED -> obstructed++;
            case LOCKED -> locked++;
            case LIMIT -> limitReached++;
            case STALE -> stale++;
        }
    }

    /** Positions that were skipped for any reason other than being unchanged. */
    public int skipped() {
        return protectedBlocks + obstructed + locked + limitReached + stale + failed;
    }
}
