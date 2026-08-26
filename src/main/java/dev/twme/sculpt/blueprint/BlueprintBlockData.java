package dev.twme.sculpt.blueprint;

import java.util.Map;

import javax.annotation.Nullable;

/** A block snapshot positioned relative to a cuboid blueprint's minimum corner. */
public record BlueprintBlockData(
    int x,
    int y,
    int z,
    @Nullable String blockKey,
    @Nullable String matchedVariantKey,
    boolean isMixed,
    int maxDepth,
    int gridN,
    @Nullable byte[] octreeData,
    @Nullable Map<String, int[]> leafCoordinates,
    @Nullable Kind kind,
    @Nullable String blockData
) {
    /** Missing in older files, where every collection entry was a SculptBlock. */
    public enum Kind { SCULPT, BLOCK }

    public BlueprintBlockData(
            int x, int y, int z, @Nullable String blockKey,
            @Nullable String matchedVariantKey, boolean isMixed,
            int maxDepth, int gridN, byte[] octreeData,
            @Nullable Map<String, int[]> leafCoordinates) {
        this(x, y, z, blockKey, matchedVariantKey, isMixed, maxDepth, gridN,
            octreeData, leafCoordinates, Kind.SCULPT, null);
    }

    public Kind resolvedKind() {
        return kind == null ? Kind.SCULPT : kind;
    }

    public boolean isSculptBlock() {
        return resolvedKind() == Kind.SCULPT;
    }

    public boolean isRegularBlock() {
        return resolvedKind() == Kind.BLOCK;
    }
}
