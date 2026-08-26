package dev.twme.sculpt.assets.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class BlockVisualShapeResolverTest {

    @Test
    void fenceUsesRenderedPostAndBarsInsteadOfCollisionBounds() {
        final Fixtures fixtures = new Fixtures()
            .json("blockstates/oak_fence.json", """
                {"multipart":[
                  {"apply":{"model":"minecraft:block/oak_fence_post"}},
                  {"when":{"north":"true"},
                   "apply":{"model":"minecraft:block/oak_fence_side"}}
                ]}
                """)
            .json("models/block/oak_fence_post.json", model("""
                {"from":[6,0,6],"to":[10,16,10]}
                """))
            .json("models/block/oak_fence_side.json", model("""
                {"from":[7,12,0],"to":[9,15,9]},
                {"from":[7,6,0],"to":[9,9,9]}
                """));
        final BlockVisualShapeResolver resolver = fixtures.resolver();

        final VisualShape post = resolver.resolve(
            "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
            .shape();
        final VisualShape north = resolver.resolve(
            "minecraft:oak_fence[east=false,north=true,south=false,waterlogged=false,west=false]")
            .shape();

        assertEquals(256, post.occupiedVoxelCount());
        assertEquals(328, north.occupiedVoxelCount());
        assertTrue(north.blocks().get(VisualShape.BlockOffset.ORIGIN)
            .occupied(7, 13, 1));
        assertFalse(north.blocks().get(VisualShape.BlockOffset.ORIGIN)
            .occupied(4, 13, 1));
    }

    @Test
    void wallLowSideUsesFourteenPixelVisualHeight() {
        final Fixtures fixtures = new Fixtures()
            .json("blockstates/cobblestone_wall.json", """
                {"multipart":[
                  {"when":{"up":"true"},"apply":{"model":"minecraft:block/wall_post"}},
                  {"when":{"north":"low|tall"},"apply":{"model":"minecraft:block/wall_side"}}
                ]}
                """)
            .json("models/block/wall_post.json", model("""
                {"from":[4,0,4],"to":[12,16,12]}
                """))
            .json("models/block/wall_side.json", model("""
                {"from":[5,0,0],"to":[11,14,8]}
                """));

        final VisualShape shape = fixtures.resolver().resolve(
            "minecraft:cobblestone_wall[east=none,north=low,south=none,up=true,waterlogged=false,west=none]")
            .shape();

        assertEquals(1360, shape.occupiedVoxelCount());
        final VoxelMask mask = shape.blocks().get(VisualShape.BlockOffset.ORIGIN);
        assertTrue(mask.occupied(6, 13, 1));
        assertFalse(mask.occupied(6, 14, 1));
    }

    @Test
    void variantPropertiesAreSubsetMatchedAndRotationIsApplied() {
        final Fixtures fixtures = new Fixtures()
            .json("blockstates/test_stairs.json", """
                {"variants":{
                  "facing=east,half=bottom,shape=straight":
                    {"model":"minecraft:block/test_stairs","y":90}
                }}
                """)
            .json("models/block/test_stairs.json", model("""
                {"from":[0,0,0],"to":[16,8,16]},
                {"from":[0,8,8],"to":[16,16,16]}
                """));

        final BlockVisualShapeResolver.Resolution resolution = fixtures.resolver().resolve(
            "minecraft:test_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");

        assertTrue(resolution.supported());
        final VoxelMask mask = resolution.shape().blocks()
            .get(VisualShape.BlockOffset.ORIGIN);
        assertEquals(3072, mask.occupiedCount());
        assertTrue(mask.occupied(12, 12, 4));
        assertFalse(mask.occupied(4, 12, 12));
    }

    @Test
    void eulerElementRotationUsesMinecraftZyxMatrixOrder() {
        final Fixtures fixtures = new Fixtures()
            .json("blockstates/euler_test.json", """
                {"variants":{"":{"model":"minecraft:block/euler_test"}}}
                """)
            .json("models/block/euler_test.json", model("""
                {"from":[0,0,0],"to":[8,8,8],"rotation":{
                  "origin":[8,8,8],"x":0,"y":90,"z":0
                }}
                """));

        final BlockVisualShapeResolver.Resolution result = fixtures.resolver().resolve(
            "minecraft:euler_test");

        assertTrue(result.supported());
        final VoxelMask mask = result.shape().blocks()
            .get(VisualShape.BlockOffset.ORIGIN);
        assertEquals(512, mask.occupiedCount());
        assertTrue(mask.occupied(4, 4, 12));
        assertFalse(mask.occupied(4, 4, 4));
    }

    @Test
    void positiveSubPixelThicknessStillOccupiesOneVoxelLayer() {
        final Fixtures fixtures = new Fixtures()
            .json("blockstates/pressed_plate.json", """
                {"variants":{"powered=true":{
                  "model":"minecraft:block/pressed_plate"
                }}}
                """)
            .json("models/block/pressed_plate.json", model("""
                {"from":[1,0,1],"to":[15,0.5,15]}
                """));

        final BlockVisualShapeResolver.Resolution result = fixtures.resolver().resolve(
            "minecraft:pressed_plate[powered=true]");

        assertTrue(result.supported());
        assertEquals(14 * 14, result.shape().occupiedVoxelCount());
        final VoxelMask mask = result.shape().blocks()
            .get(VisualShape.BlockOffset.ORIGIN);
        assertTrue(mask.occupied(1, 0, 1));
        assertFalse(mask.occupied(1, 1, 1));
    }

    @Test
    void inheritedElementsUseChildStateAndMultipartOrConditions() {
        final Fixtures fixtures = new Fixtures()
            .json("blockstates/inherited_test.json", """
                {"multipart":[
                  {"when":{"OR":[{"facing":"north"},{"powered":"true"}]},
                   "apply":{"model":"minecraft:block/inherited_child"}}
                ]}
                """)
            .json("models/block/inherited_parent.json", model("""
                {"from":[2,1,3],"to":[6,9,8]}
                """))
            .json("models/block/inherited_child.json", """
                {"parent":"minecraft:block/inherited_parent"}
                """);

        final BlockVisualShapeResolver.Resolution result = fixtures.resolver().resolve(
            "minecraft:inherited_test[facing=south,powered=true]");

        assertTrue(result.supported());
        assertEquals(4 * 8 * 5, result.shape().occupiedVoxelCount());
    }

    @Test
    void multipartAndRequiresEveryNestedCondition() {
        final Fixtures fixtures = new Fixtures()
            .json("blockstates/and_test.json", """
                {"multipart":[
                  {"apply":{"model":"minecraft:block/base"}},
                  {"when":{"AND":[
                     {"facing":"north"},
                     {"occupied":"true"}
                   ]},"apply":{"model":"minecraft:block/attachment"}}
                ]}
                """)
            .json("models/block/base.json", model("""
                {"from":[0,0,0],"to":[8,8,8]}
                """))
            .json("models/block/attachment.json", model("""
                {"from":[8,8,8],"to":[16,16,16]}
                """));
        final BlockVisualShapeResolver resolver = fixtures.resolver();

        final VisualShape both = resolver.resolve(
            "minecraft:and_test[facing=north,occupied=true]").shape();
        final VisualShape one = resolver.resolve(
            "minecraft:and_test[facing=north,occupied=false]").shape();

        assertEquals(1024, both.occupiedVoxelCount());
        assertEquals(512, one.occupiedVoxelCount());
    }

    @Test
    void childElementsRemainValidWhenAbstractParentHasNoElements() {
        final Fixtures fixtures = new Fixtures()
            .json("blockstates/template_child.json", """
                {"variants":{"":{"model":"minecraft:block/template_child"}}}
                """)
            .json("models/block/abstract_parent.json", "{}")
            .json("models/block/template_child.json", """
                {"parent":"minecraft:block/abstract_parent","elements":[
                  {"from":[0,0,0],"to":[16,16,16]}
                ]}
                """);

        final BlockVisualShapeResolver.Resolution result = fixtures.resolver().resolve(
            "minecraft:template_child");

        assertTrue(result.supported());
        assertTrue(result.shape().isSingleFullBlock());
    }

    @Test
    void differentlyShapedWeightedAlternativesAreRejected() {
        final Fixtures fixtures = new Fixtures()
            .json("blockstates/random_test.json", """
                {"variants":{"": [
                  {"model":"minecraft:block/random_a","weight":1},
                  {"model":"minecraft:block/random_b","weight":1}
                ]}}
                """)
            .json("models/block/random_a.json", model("""
                {"from":[0,0,0],"to":[8,8,8]}
                """))
            .json("models/block/random_b.json", model("""
                {"from":[8,8,8],"to":[16,16,16]}
                """));

        final BlockVisualShapeResolver.Resolution result = fixtures.resolver().resolve(
            "minecraft:random_test");

        assertFalse(result.supported());
        assertEquals(BlockVisualShapeResolver.Failure.AMBIGUOUS_WEIGHTED_MODEL,
            result.failure());
    }

    @Test
    void transparentPixelsOnZeroThicknessFacesDoNotCreateCells() throws Exception {
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 8; x++) image.setRGB(x, y, 0xffffffff);
        }
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        final Fixtures fixtures = new Fixtures()
            .json("blockstates/plane_test.json", """
                {"variants":{"":{"model":"minecraft:block/plane_test"}}}
                """)
            .json("models/block/plane_test.json", """
                {"textures":{"plane":{
                   "sprite":"minecraft:block/plane_test",
                   "force_translucent":true
                 }},"elements":[
                  {"from":[0,0,8],"to":[16,16,8],"faces":{
                    "north":{"texture":"#plane","uv":[0,0,16,16]},
                    "south":{"texture":"#plane","uv":[0,0,16,16]}
                  }}
                ]}
                """)
            .bytes("textures/block/plane_test.png", png.toByteArray());

        final BlockVisualShapeResolver.Resolution result = fixtures.resolver().resolve(
            "minecraft:plane_test");

        assertTrue(result.supported());
        assertEquals(128, result.shape().occupiedVoxelCount());
    }

    @Test
    void specialRendererWithoutElementsIsReportedAsUnsupported() {
        final Fixtures fixtures = new Fixtures()
            .json("blockstates/special_test.json", """
                {"variants":{"":{"model":"minecraft:block/special_test"}}}
                """)
            .json("models/block/special_test.json", """
                {"parent":"minecraft:builtin/entity"}
                """);

        final BlockVisualShapeResolver.Resolution result = fixtures.resolver().resolve(
            "minecraft:special_test");

        assertFalse(result.supported());
        assertEquals(BlockVisualShapeResolver.Failure.NO_ELEMENTS, result.failure());
    }

    private static String model(final String elements) {
        return "{\"elements\":[" + elements + "]}";
    }

    private static final class Fixtures {
        private final Map<String, byte[]> assets = new HashMap<>();

        private Fixtures json(final String path, final String json) {
            return bytes(path, json.getBytes(StandardCharsets.UTF_8));
        }

        private Fixtures bytes(final String path, final byte[] value) {
            assets.put(path, value.clone());
            return this;
        }

        private BlockVisualShapeResolver resolver() {
            return new BlockVisualShapeResolver(path -> {
                final byte[] value = assets.get(path);
                if (value == null) throw new java.io.IOException("missing fixture " + path);
                return value.clone();
            }, Logger.getLogger("visual-shape-test"));
        }
    }
}
