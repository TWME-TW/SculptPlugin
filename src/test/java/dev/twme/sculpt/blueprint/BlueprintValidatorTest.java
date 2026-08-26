package dev.twme.sculpt.blueprint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BlueprintValidatorTest {

    @Test
    void acceptsMinimalStructurallyValidBlueprint() {
        assertTrue(BlueprintValidator.validate(blueprint(0, 1, new byte[]{(byte) 0x80}, null)).valid());
    }

    @Test
    void rejectsGridDepthMismatch() {
        assertFalse(BlueprintValidator.validate(
            blueprint(2, 8, new byte[]{(byte) 0x80}, null)).valid());
    }

    @Test
    void rejectsTrailingOctreePayload() {
        assertFalse(BlueprintValidator.validate(
            blueprint(0, 1, new byte[]{(byte) 0x80, 0}, null)).valid());
    }

    @Test
    void rejectsCoordinateForUnknownLeaf() {
        assertFalse(BlueprintValidator.validate(blueprint(
            0, 1, new byte[]{(byte) 0x80}, Map.of("0", new int[]{0, 0, 0}))).valid());
    }

    @Test
    void rejectsNonCardinalReferenceFacing() {
        BlueprintData base = blueprint(0, 1, new byte[]{(byte) 0x80}, null);
        BlueprintData invalid = new BlueprintData(
            base.blueprintId(), base.name(), base.description(),
            base.createdTimestamp(), base.lastModifiedTimestamp(),
            base.minecraftVersion(), base.blockKey(), base.matchedVariantKey(),
            base.isMixed(), base.maxDepth(), base.gridN(), base.octreeData(),
            base.leafCoordinates(), "UP", base.visibility(), base.editToken());

        assertFalse(BlueprintValidator.validate(invalid).valid());
    }

    @Test
    void acceptsCuboidBlueprintAndRejectsDuplicatePositions() {
        BlueprintBlockData first = block(0, 0, 0);
        BlueprintBlockData second = block(1, 0, 0);
        assertTrue(BlueprintValidator.validate(cuboid(List.of(first, second), 2, 1, 1)).valid());
        assertFalse(BlueprintValidator.validate(cuboid(List.of(first, first), 2, 1, 1)).valid());
    }

    @Test
    void treatsMissingKindAsLegacySculptEntry() {
        BlueprintBlockData legacy = new BlueprintBlockData(
            0, 0, 0, "minecraft:stone", null, false,
            0, 1, new byte[]{(byte) 0x80}, null, null, null);

        assertTrue(legacy.isSculptBlock());
        assertTrue(BlueprintValidator.validate(cuboid(List.of(legacy), 1, 1, 1)).valid());
    }

    @Test
    void rejectsCuboidBlockOutsideDeclaredDimensions() {
        assertFalse(BlueprintValidator.validate(
            cuboid(List.of(block(2, 0, 0)), 2, 1, 1)).valid());
    }

    @Test
    void acceptsRegularBlockWithCompleteBlockData() {
        BlueprintBlockData block = regularBlock(
            "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]");

        assertTrue(BlueprintValidator.validate(cuboid(List.of(block), 1, 1, 1)).valid());
    }

    @Test
    void rejectsRegularBlockWithoutBlockDataOrWithMismatchedMaterial() {
        BlueprintBlockData missing = new BlueprintBlockData(
            0, 0, 0, "minecraft:stone", null, false,
            0, 1, null, null, BlueprintBlockData.Kind.BLOCK, null);
        BlueprintBlockData mismatched = new BlueprintBlockData(
            0, 0, 0, "minecraft:stone", null, false,
            0, 1, null, null, BlueprintBlockData.Kind.BLOCK, "minecraft:dirt");

        assertFalse(BlueprintValidator.validate(cuboid(List.of(missing), 1, 1, 1)).valid());
        assertFalse(BlueprintValidator.validate(cuboid(List.of(mismatched), 1, 1, 1)).valid());
    }

    @Test
    void rejectsAirAsARegularBlockEntry() {
        assertFalse(BlueprintValidator.validate(cuboid(
            List.of(regularBlock("minecraft:air")), 1, 1, 1)).valid());
    }

    private static BlueprintBlockData block(int x, int y, int z) {
        return new BlueprintBlockData(
            x, y, z, "minecraft:stone", null, false,
            0, 1, new byte[]{(byte) 0x80}, null);
    }

    private static BlueprintBlockData regularBlock(String blockData) {
        String blockKey = blockData.split("\\[", 2)[0];
        return new BlueprintBlockData(
            0, 0, 0, blockKey, null, false,
            0, 1, null, null, BlueprintBlockData.Kind.BLOCK, blockData);
    }

    private static BlueprintData cuboid(
            List<BlueprintBlockData> blocks, int sizeX, int sizeY, int sizeZ) {
        BlueprintBlockData representative = blocks.stream()
            .filter(BlueprintBlockData::isSculptBlock)
            .findFirst().orElse(block(0, 0, 0));
        return new BlueprintData(
            UUID.randomUUID(), "cuboid", null, 1, 1, "1.21.11",
            representative.blockKey(), null, false, representative.maxDepth(),
            representative.gridN(), representative.octreeData(), null,
            blocks, sizeX, sizeY, sizeZ, "NORTH",
            BlueprintData.Visibility.PRIVATE, null);
    }

    private static BlueprintData blueprint(int maxDepth, int gridN, byte[] octree,
                                            Map<String, int[]> coordinates) {
        return new BlueprintData(
            UUID.randomUUID(), "test", null, 1, 1, "1.21.11",
            "minecraft:stone", null, false, maxDepth, gridN, octree,
            coordinates, BlueprintData.Visibility.PRIVATE, null);
    }
}
