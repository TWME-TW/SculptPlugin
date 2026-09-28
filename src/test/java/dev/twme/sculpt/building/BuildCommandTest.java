package dev.twme.sculpt.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class BuildCommandTest {

    @Test
    void surfaceRowsPreferSquareNetsThenTwoRows() {
        assertEquals(2, BuildCommand.surfaceRows(4, 0));
        assertEquals(3, BuildCommand.surfaceRows(9, 0));
        assertEquals(4, BuildCommand.surfaceRows(16, 0));
        assertEquals(2, BuildCommand.surfaceRows(6, 0));
        assertEquals(-1, BuildCommand.surfaceRows(5, 0));
        assertEquals(3, BuildCommand.surfaceRows(12, 3));
        assertEquals(-1, BuildCommand.surfaceRows(12, 5));
        assertEquals(-1, BuildCommand.surfaceRows(4, 4), "one column is not a surface");
    }

    @Test
    void cylinderRadiusIsMeasuredFromTheAxis() {
        assertEquals(3.0, BuildCommand.distanceToAxis(new Vector3d(3, 5, 0),
            new Vector3d(0, 0, 0), new Vector3d(0, 10, 0)), 1e-9);
    }

    @Test
    void optionsParseFlagsInAnyOrder() {
        final BuildCommand.Options options = BuildCommand.Options.parse(new String[]{
            "sphere", "--hollow", "--material", "stone", "--thickness", "2.5", "--carve"}, 1);
        assertNull(options.errorKey());
        assertEquals(2.5, options.thickness());
        assertTrue(options.hollow());
        assertTrue(options.carve());
        assertEquals("stone", options.material());
    }

    @Test
    void optionsReportMistakes() {
        assertEquals("building.options.unknown", BuildCommand.Options.parse(
            new String[]{"plane", "--wide"}, 1).errorKey());
        assertEquals("building.options.missing_value", BuildCommand.Options.parse(
            new String[]{"plane", "--thickness"}, 1).errorKey());
        assertEquals("building.options.invalid_number", BuildCommand.Options.parse(
            new String[]{"plane", "--thickness", "0.5"}, 1).errorKey());
        assertEquals("building.options.invalid_number", BuildCommand.Options.parse(
            new String[]{"surface", "--rows", "one"}, 1).errorKey());
    }

    @Test
    void sphereRasterizerUsesCenterAndSurfacePoint() {
        final List<Vector3d> points = List.of(new Vector3d(0.5, 0.5, 0.5),
            new Vector3d(3.5, 0.5, 0.5));
        final CellVolume volume = new CellVolume(1, 1000, 100_000);
        BuildCommand.rasterizer(BuildCommand.Shape.SPHERE, points,
            BuildCommand.Options.parse(new String[]{"sphere"}, 1), 0).accept(volume);
        assertTrue(volume.contains(3, 0, 0));
        assertTrue(volume.contains(0, 0, 0));
    }
}
