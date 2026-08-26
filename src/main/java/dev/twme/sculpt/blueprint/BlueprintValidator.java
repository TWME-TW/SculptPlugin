package dev.twme.sculpt.blueprint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import dev.twme.sculpt.core.OctreeNode;

/** Central validation boundary for blueprints loaded from disk or the network. */
public final class BlueprintValidator {

    public static final int MAX_DEPTH = 4;
    public static final int MAX_LEAVES = 4_096;
    public static final int MAX_OCTREE_BYTES = 1_048_576;
    public static final int MAX_BLOCKS = 4_096;
    public static final int MAX_SELECTION_VOLUME = 4_096;
    public static final int MAX_TOTAL_LEAVES = 65_536;
    public static final int MAX_TOTAL_OCTREE_BYTES = 8_388_608;
    public static final long MAX_FILE_BYTES = 12_582_912L;

    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 4_096;
    private static final int MAX_METADATA_LENGTH = 512;
    private static final int MAX_BLOCK_DATA_LENGTH = 4_096;
    private static final Pattern BLOCK_KEY = Pattern.compile(
        "[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern BLOCK_DATA = Pattern.compile(
        "[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_=,.-]+])?");
    private static final Pattern NODE_PATH = Pattern.compile("(?:[0-7](?:\\.[0-7])*)?");

    private BlueprintValidator() {}

    public static ValidationResult validate(@Nullable BlueprintData data) {
        if (data == null) return invalid("blueprint is null");
        if (data.blueprintId() == null) return invalid("blueprintId is missing");
        if (data.name() == null || data.name().isBlank()
                || data.name().codePointCount(0, data.name().length()) > MAX_NAME_LENGTH) {
            return invalid("name must contain 1-64 characters");
        }
        if (tooLong(data.description(), MAX_DESCRIPTION_LENGTH)) {
            return invalid("description is too long");
        }
        if (tooLong(data.minecraftVersion(), 64)
                || tooLong(data.matchedVariantKey(), MAX_METADATA_LENGTH)
                || tooLong(data.editToken(), MAX_METADATA_LENGTH)) {
            return invalid("metadata is too long");
        }
        if (data.referenceFacing() != null
                && !java.util.Set.of("NORTH", "SOUTH", "EAST", "WEST")
                    .contains(data.referenceFacing().toUpperCase(java.util.Locale.ROOT))) {
            return invalid("referenceFacing must be a horizontal cardinal direction");
        }
        BlockValidation representative = validateBlock(
            data.blockKey(), data.matchedVariantKey(), data.maxDepth(), data.gridN(),
            data.octreeData(), data.leafCoordinates());
        if (!representative.valid()) return invalid(representative.reason());

        List<BlueprintBlockData> blocks = data.blocks();
        if (blocks == null || blocks.isEmpty()) {
            return new ValidationResult(true, null, representative.leafCount());
        }
        if (blocks.size() > MAX_BLOCKS) return invalid("blocks contains too many entries");
        if (data.sizeX() < 1 || data.sizeY() < 1 || data.sizeZ() < 1) {
            return invalid("blueprint dimensions must be positive");
        }
        long volume = (long) data.sizeX() * data.sizeY() * data.sizeZ();
        if (volume > MAX_SELECTION_VOLUME) return invalid("blueprint volume is too large");
        if (blocks.size() > volume) return invalid("blocks exceeds the blueprint volume");

        Set<BlockCoordinate> occupied = new HashSet<>();
        int totalLeaves = 0;
        long totalBytes = 0;
        for (BlueprintBlockData block : blocks) {
            if (block == null) return invalid("blocks contains a null entry");
            if (block.x() < 0 || block.x() >= data.sizeX()
                    || block.y() < 0 || block.y() >= data.sizeY()
                    || block.z() < 0 || block.z() >= data.sizeZ()) {
                return invalid("blocks contains an out-of-range position");
            }
            if (!occupied.add(new BlockCoordinate(block.x(), block.y(), block.z()))) {
                return invalid("blocks contains a duplicate position");
            }
            BlockValidation result = block.isRegularBlock()
                ? validateRegularBlock(block.blockKey(), block.blockData())
                : validateBlock(
                    block.blockKey(), block.matchedVariantKey(), block.maxDepth(), block.gridN(),
                    block.octreeData(), block.leafCoordinates());
            if (!result.valid()) return invalid("invalid block entry: " + result.reason());
            totalLeaves += result.leafCount();
            if (block.isSculptBlock()) totalBytes += block.octreeData().length;
            if (totalLeaves > MAX_TOTAL_LEAVES) return invalid("total leaf count is too large");
            if (totalBytes > MAX_TOTAL_OCTREE_BYTES) {
                return invalid("total octreeData size is too large");
            }
        }

        return new ValidationResult(true, null, totalLeaves);
    }

    private static BlockValidation validateRegularBlock(
            @Nullable String blockKey, @Nullable String blockData) {
        if (blockKey == null || blockKey.length() > MAX_METADATA_LENGTH
                || !BLOCK_KEY.matcher(blockKey).matches()) {
            return BlockValidation.invalid("blockKey is invalid");
        }
        if (blockData == null || blockData.isBlank()
                || blockData.length() > MAX_BLOCK_DATA_LENGTH
                || !BLOCK_DATA.matcher(blockData).matches()) {
            return BlockValidation.invalid("blockData is invalid");
        }
        String materialKey = blockData.split("\\[", 2)[0];
        if (!blockKey.equals(materialKey)) {
            return BlockValidation.invalid("blockData does not match blockKey");
        }
        if (Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
                .contains(materialKey)) {
            return BlockValidation.invalid("air must be represented by an empty position");
        }
        return new BlockValidation(true, null, 0);
    }

    public static void requireValid(@Nullable BlueprintData data) {
        ValidationResult result = validate(data);
        if (!result.valid()) throw new IllegalArgumentException(result.reason());
    }

    private static boolean tooLong(@Nullable String value, int maximum) {
        return value != null && value.length() > maximum;
    }

    private static int pathDepth(String path) {
        return path.isEmpty() ? 0 : path.split("\\.").length;
    }

    private static BlockValidation validateBlock(
            @Nullable String blockKey, @Nullable String matchedVariantKey,
            int maxDepth, int gridN, @Nullable byte[] octreeData,
            @Nullable Map<String, int[]> coordinates) {
        if (blockKey != null && (blockKey.length() > MAX_METADATA_LENGTH
                || !BLOCK_KEY.matcher(blockKey).matches())) {
            return BlockValidation.invalid("blockKey is invalid");
        }
        if (tooLong(matchedVariantKey, MAX_METADATA_LENGTH)) {
            return BlockValidation.invalid("matchedVariantKey is too long");
        }
        if (maxDepth < 0 || maxDepth > MAX_DEPTH) {
            return BlockValidation.invalid("maxDepth must be between 0 and 4");
        }
        if (gridN != 1 << maxDepth) {
            return BlockValidation.invalid("gridN does not match maxDepth");
        }
        if (octreeData == null || octreeData.length == 0
                || octreeData.length > MAX_OCTREE_BYTES) {
            return BlockValidation.invalid("octreeData size is invalid");
        }

        OctreeNode root;
        try {
            root = OctreeNode.deserialize(octreeData, maxDepth);
        } catch (RuntimeException e) {
            return BlockValidation.invalid("octreeData is malformed: " + e.getMessage());
        }
        ArrayList<OctreeNode> leaves = new ArrayList<>();
        root.collectAllLeaves(leaves);
        if (leaves.isEmpty() || leaves.size() > MAX_LEAVES) {
            return BlockValidation.invalid("leaf count is invalid");
        }

        if (coordinates != null) {
            if (coordinates.size() > leaves.size()) {
                return BlockValidation.invalid("leafCoordinates contains too many entries");
            }
            for (Map.Entry<String, int[]> entry : coordinates.entrySet()) {
                String path = entry.getKey();
                int[] coordinate = entry.getValue();
                if (path == null || !NODE_PATH.matcher(path).matches()
                        || pathDepth(path) > maxDepth) {
                    return BlockValidation.invalid("leafCoordinates contains an invalid path");
                }
                OctreeNode node;
                try {
                    node = OctreeNode.fromPath(root, path);
                } catch (RuntimeException e) {
                    return BlockValidation.invalid("leafCoordinates path cannot be resolved");
                }
                if (node == null || !node.isLeaf()) {
                    return BlockValidation.invalid("leafCoordinates path does not identify a leaf");
                }
                if (coordinate == null || coordinate.length != 3
                        || !insideGrid(coordinate[0], gridN)
                        || !insideGrid(coordinate[1], gridN)
                        || !insideGrid(coordinate[2], gridN)) {
                    return BlockValidation.invalid(
                        "leafCoordinates contains an out-of-range coordinate");
                }
            }
        }
        return new BlockValidation(true, null, leaves.size());
    }

    private static boolean insideGrid(int value, int gridN) {
        return value >= 0 && value < gridN;
    }

    private static ValidationResult invalid(String reason) {
        return new ValidationResult(false, reason, 0);
    }

    public record ValidationResult(boolean valid, @Nullable String reason, int leafCount) {}

    private record BlockValidation(boolean valid, @Nullable String reason, int leafCount) {
        private static BlockValidation invalid(String reason) {
            return new BlockValidation(false, reason, 0);
        }
    }

    private record BlockCoordinate(int x, int y, int z) {}
}
