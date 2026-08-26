package dev.twme.sculpt.render.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.FaceDir;
import dev.twme.textdisplayshape.util.TRSResult;
import dev.twme.textdisplayshape.util.TextDisplayUtil;

class TextDisplayBlockRendererTest {

    @Test
    void faceShadingPreservesTextureAlpha() {
        assertEquals(0x80204060,
            TextDisplayBlockRenderer.shade(0x804080C0, 0.5f));
    }

    @Test
    void blockRotationMapsCanonicalFaceToWorldFace() {
        assertEquals(FaceDir.NORTH, TextDisplayBlockRenderer.rotateFace(
            FaceDir.NORTH, new Quaternionf()));
        assertEquals(FaceDir.SOUTH, TextDisplayBlockRenderer.rotateFace(
            FaceDir.NORTH, new Quaternionf().rotateY((float) Math.PI)));
    }

    @Test
    void relightClearsOnlyExistingBrightnessOverrides() {
        final AtomicReference<Display.Brightness> overridden =
            new AtomicReference<>(new Display.Brightness(15, 15));
        final AtomicReference<Display.Brightness> automatic =
            new AtomicReference<>();

        final var result = TextDisplayBlockRenderer.clearBrightnessOverrides(
            List.of(List.of(
                textDisplay(overridden), textDisplay(automatic))));

        assertEquals(2, result.displaysChecked());
        assertEquals(1, result.displaysUpdated());
        assertNull(overridden.get());
        assertNull(automatic.get());
    }

    @Test
    void adjacentVoxelsWithTheSameMaterialCullTheirSharedFace() {
        final BakeKey glass = BakeKey.untinted(BlockKey.of("minecraft:glass"));
        final var material = new TextDisplayBlockRenderer.SurfaceMaterial(glass, null);
        final var fixture = eastBoundary(material, material);

        assertFalse(TextDisplayBlockRenderer.isExposed(
            fixture.snapshot(), fixture.leaf(), FaceDir.EAST, FaceDir.EAST,
            0, 0, 1, 1));
    }

    @Test
    void adjacentVoxelsWithDifferentMaterialsKeepTheirSharedFace() {
        final var red = new TextDisplayBlockRenderer.SurfaceMaterial(
            BakeKey.untinted(BlockKey.of("minecraft:red_stained_glass")), null);
        final var blue = new TextDisplayBlockRenderer.SurfaceMaterial(
            BakeKey.untinted(BlockKey.of("minecraft:blue_stained_glass")), null);
        final var fixture = eastBoundary(red, blue);

        assertTrue(TextDisplayBlockRenderer.isExposed(
            fixture.snapshot(), fixture.leaf(), FaceDir.EAST, FaceDir.EAST,
            0, 0, 1, 1));
    }

    @Test
    void differentBiomeTintsAlsoCreateAVisibleMaterialBoundary() {
        final BlockKey leaves = BlockKey.of("minecraft:oak_leaves");
        final var first = new TextDisplayBlockRenderer.SurfaceMaterial(
            new BakeKey(leaves, 0xff44aa33), null);
        final var second = new TextDisplayBlockRenderer.SurfaceMaterial(
            new BakeKey(leaves, 0xff3377bb), null);
        final var fixture = eastBoundary(first, second);

        assertTrue(TextDisplayBlockRenderer.isExposed(
            fixture.snapshot(), fixture.leaf(), FaceDir.EAST, FaceDir.EAST,
            0, 0, 1, 1));
    }

    @Test
    void seamOverlapExpandsEveryEdgeWithoutMovingTheSurfacePlane() {
        final float overlap = TextDisplayBlockRenderer.SEAM_OVERLAP;

        final TextDisplayBlockRenderer.QuadPoints expanded =
            TextDisplayBlockRenderer.overlapInPlane(
                new Vector3f(0f, 0f, 0.25f),
                new Vector3f(1f, 0f, 0.25f),
                new Vector3f(0f, 1f, 0.25f),
                overlap);

        assertVectorEquals(
            new Vector3f(-overlap, -overlap, 0.25f), expanded.p1());
        assertVectorEquals(
            new Vector3f(1f + overlap, -overlap, 0.25f), expanded.p2());
        assertVectorEquals(
            new Vector3f(-overlap, 1f + overlap, 0.25f), expanded.p3());
        assertEquals(1f + overlap * 2f,
            expanded.p1().distance(expanded.p2()), 1.0e-6f);
        assertEquals(1f + overlap * 2f,
            expanded.p1().distance(expanded.p3()), 1.0e-6f);
    }

    @Test
    void seamOverlapAlsoPreservesARotatedSurfacePlane() {
        final TextDisplayBlockRenderer.QuadPoints expanded =
            TextDisplayBlockRenderer.overlapInPlane(
                new Vector3f(0.3f, 0.2f, 0.4f),
                new Vector3f(0.3f, 0.2f, 0.9f),
                new Vector3f(0.3f, 0.7f, 0.4f),
                TextDisplayBlockRenderer.SEAM_OVERLAP);

        assertEquals(0.3f, expanded.p1().x, 1.0e-7f);
        assertEquals(0.3f, expanded.p2().x, 1.0e-7f);
        assertEquals(0.3f, expanded.p3().x, 1.0e-7f);
        assertEquals(0.5f + TextDisplayBlockRenderer.SEAM_OVERLAP * 2f,
            expanded.p1().distance(expanded.p2()), 1.0e-6f);
        assertEquals(0.5f + TextDisplayBlockRenderer.SEAM_OVERLAP * 2f,
            expanded.p1().distance(expanded.p3()), 1.0e-6f);
    }

    @Test
    void seamOverlapCanMiterOnlyTheOuterFaceEdges() {
        final float overlap = TextDisplayBlockRenderer.SEAM_OVERLAP;

        final TextDisplayBlockRenderer.QuadPoints expanded =
            TextDisplayBlockRenderer.overlapInPlane(
                new Vector3f(0.25f, 0.25f, 0f),
                new Vector3f(1f, 0.25f, 0f),
                new Vector3f(0.25f, 0.75f, 0f),
                overlap,
                false, true, false, false);

        assertVectorEquals(
            new Vector3f(0.25f, 0.25f, 0f), expanded.p1());
        assertVectorEquals(
            new Vector3f(1f + overlap, 0.25f, 0f), expanded.p2());
        assertVectorEquals(
            new Vector3f(0.25f, 0.75f, 0f), expanded.p3());
    }

    @Test
    void rootLocalCoordinatesRetainGrid16PrecisionAtLargeWorldDistances() {
        final float farCoordinate = 30_000_000f;
        assertEquals(farCoordinate, farCoordinate + 1f / 16f,
            "an absolute float point loses one grid=16 cell this far out");

        final Vector3f local = TextDisplayBlockRenderer.rootLocalPoint(
            new Vector3f(1f / 16f, 0.5f, 1f));

        assertVectorEquals(new Vector3f(-7f / 16f, 0f, 0.5f), local);
    }

    @Test
    void rootLocalTransformationMatchesThePreviousWorldGeometry() {
        final Vector3f p1 = new Vector3f(0.25f, 0.125f, 0.75f);
        final Vector3f p2 = new Vector3f(0.75f, 0.125f, 0.75f);
        final Vector3f p3 = new Vector3f(0.25f, 0.625f, 0.75f);
        final var local = TextDisplayBlockRenderer.quadTransformation(
            new TextDisplayBlockRenderer.PixelQuad(p1, p2, p3, 0xFFFFFFFF));

        final int blockX = 12;
        final int blockY = 64;
        final int blockZ = -8;
        final TRSResult previous = TextDisplayUtil.computeParallelogramTRS(
            new Vector3f(p1).add(blockX, blockY, blockZ),
            new Vector3f(p2).add(blockX, blockY, blockZ),
            new Vector3f(p3).add(blockX, blockY, blockZ));
        final Vector3f previousRelative = new Vector3f(previous.translation())
            .sub(blockX + 0.5f, blockY + 0.5f, blockZ + 0.5f);

        assertVectorEquals(previousRelative, local.getTranslation(), 1.0e-5f);
        assertVectorEquals(previous.scale(), local.getScale(), 1.0e-5f);
        assertQuaternionEquivalent(
            previous.leftRotation(), local.getLeftRotation());
        assertQuaternionEquivalent(
            previous.rightRotation(), local.getRightRotation());
    }

    @Test
    void seamOverlapRejectsInvalidDistances() {
        assertThrows(IllegalArgumentException.class,
            () -> TextDisplayBlockRenderer.overlapInPlane(
                new Vector3f(), new Vector3f(1f, 0f, 0f),
                new Vector3f(0f, 1f, 0f), -0.1f));
    }

    @Test
    void renderDeltaRetainsUnchangedPlanesAndTouchesOnlyDifferences() {
        final TextDisplayBlockRenderer.RenderDelta<String> delta =
            TextDisplayBlockRenderer.computeDelta(
                List.of("unchanged", "removed"),
                List.of("unchanged", "added"));

        assertEquals(List.of("added"), delta.additions());
        assertEquals(List.of("removed"), delta.removals());
    }

    @Test
    void identicalRenderPlanProducesNoEntityChanges() {
        final TextDisplayBlockRenderer.RenderDelta<String> delta =
            TextDisplayBlockRenderer.computeDelta(
                List.of("first", "second"),
                List.of("first", "second"));

        assertEquals(List.of(), delta.additions());
        assertEquals(List.of(), delta.removals());
    }

    @Test
    void incrementalUpdateCreatesEveryAdditionBeforeRemovingStalePlanes() {
        final Map<String, String> current = new LinkedHashMap<>(Map.of(
            "unchanged", "entity-unchanged",
            "removed", "entity-removed"));
        final TextDisplayBlockRenderer.RenderDelta<String> delta =
            TextDisplayBlockRenderer.computeDelta(
                current.keySet(), List.of("unchanged", "added"));
        final List<String> operations = new ArrayList<>();

        final boolean applied = TextDisplayBlockRenderer.applyDeltaAdditionsFirst(
            delta,
            current,
            key -> {
                operations.add("spawn:" + key);
                return "entity-" + key;
            },
            entity -> operations.add("remove:" + entity),
            () -> true);

        assertTrue(applied);
        assertEquals(List.of("spawn:added", "remove:entity-removed"), operations);
        assertEquals(Map.of(
            "unchanged", "entity-unchanged",
            "added", "entity-added"), current);
    }

    @Test
    void failedAdditionRollsBackNewPlanesAndKeepsOldRender() {
        final Map<String, String> current = new LinkedHashMap<>(Map.of(
            "removed", "entity-removed"));
        final TextDisplayBlockRenderer.RenderDelta<String> delta =
            TextDisplayBlockRenderer.computeDelta(
                current.keySet(), List.of("first", "second"));
        final List<String> operations = new ArrayList<>();

        assertThrows(IllegalStateException.class,
            () -> TextDisplayBlockRenderer.applyDeltaAdditionsFirst(
                delta,
                current,
                key -> {
                    operations.add("spawn:" + key);
                    if ("second".equals(key)) {
                        throw new IllegalStateException("simulated spawn failure");
                    }
                    return "entity-" + key;
                },
                entity -> operations.add("remove:" + entity),
                () -> true));

        assertEquals(List.of(
            "spawn:first", "spawn:second", "remove:entity-first"), operations);
        assertEquals(Map.of("removed", "entity-removed"), current);
    }

    private static BoundaryFixture eastBoundary(
            final TextDisplayBlockRenderer.SurfaceMaterial current,
            final TextDisplayBlockRenderer.SurfaceMaterial neighbor) {
        final TextDisplayBlockRenderer.SurfaceMaterial[] materials =
            new TextDisplayBlockRenderer.SurfaceMaterial[16 * 16 * 16];
        // Put the leaf one voxel inside the 16^3 volume so its east face
        // samples the adjacent voxel instead of the outer block boundary.
        materials[7] = current;
        materials[8] = neighbor;
        final var leaf = new TextDisplayBlockRenderer.LeafSnapshot(
            current.bakeKey(), 16, new ChunkCoord(0, 0, 0),
            7, 0, 0, 1, current);
        final var snapshot = new TextDisplayBlockRenderer.Snapshot(
            List.of(leaf), materials, new Quaternionf());
        return new BoundaryFixture(snapshot, leaf);
    }

    private record BoundaryFixture(
        TextDisplayBlockRenderer.Snapshot snapshot,
        TextDisplayBlockRenderer.LeafSnapshot leaf
    ) {}

    private static TextDisplay textDisplay(
            final AtomicReference<Display.Brightness> brightness) {
        return (TextDisplay) java.lang.reflect.Proxy.newProxyInstance(
            TextDisplay.class.getClassLoader(),
            new Class<?>[] {TextDisplay.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "isValid" -> true;
                case "getBrightness" -> brightness.get();
                case "setBrightness" -> {
                    brightness.set((Display.Brightness) arguments[0]);
                    yield null;
                }
                case "toString" -> "TextDisplayBrightnessStub";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static void assertVectorEquals(
            final Vector3f expected,
            final Vector3f actual) {
        assertVectorEquals(expected, actual, 1.0e-7f);
    }

    private static void assertVectorEquals(
            final Vector3f expected,
            final Vector3f actual,
            final float tolerance) {
        assertEquals(expected.x, actual.x, tolerance);
        assertEquals(expected.y, actual.y, tolerance);
        assertEquals(expected.z, actual.z, tolerance);
    }

    private static void assertQuaternionEquivalent(
            final Quaternionf expected,
            final Quaternionf actual) {
        assertEquals(1f, Math.abs(expected.dot(actual)), 1.0e-6f);
    }
}
