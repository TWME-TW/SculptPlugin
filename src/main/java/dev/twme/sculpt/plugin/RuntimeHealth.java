package dev.twme.sculpt.plugin;

/** Immutable snapshot of registry and runtime-bake readiness. */
public record RuntimeHealth(
        Status status,
        int configuredGrid,
        int configuredGridBlocks,
        int totalIndexedBlocks,
        boolean runtimeBakeEnabled,
        String failure
) {

    public enum Status { LOADING, READY, DEGRADED, FAILED }

    public static RuntimeHealth loading(final int configuredGrid) {
        return new RuntimeHealth(Status.LOADING, configuredGrid, 0, 0, false, null);
    }

    public static RuntimeHealth evaluate(final int configuredGrid,
                                         final int configuredGridBlocks,
                                         final int totalIndexedBlocks,
                                         final boolean runtimeBakeEnabled) {
        // Resolution 1 edits whole blocks and never renders textured cells.
        final Status status = configuredGrid == 1
            || configuredGridBlocks > 0 || runtimeBakeEnabled
            ? Status.READY : Status.DEGRADED;
        return new RuntimeHealth(status, configuredGrid, configuredGridBlocks,
            totalIndexedBlocks, runtimeBakeEnabled, null);
    }

    public static RuntimeHealth failed(final int configuredGrid, final Throwable cause) {
        final String message = cause == null || cause.getMessage() == null
            ? "unknown error" : cause.getMessage();
        return new RuntimeHealth(Status.FAILED, configuredGrid, 0, 0, false, message);
    }
}
