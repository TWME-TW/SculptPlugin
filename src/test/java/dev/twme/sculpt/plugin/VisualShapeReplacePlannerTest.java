package dev.twme.sculpt.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.twme.sculpt.assets.shape.VisualShape;
import dev.twme.sculpt.assets.shape.VoxelMask;
import dev.twme.sculpt.plugin.VisualShapeReplacePlanner.Bounds;
import dev.twme.sculpt.plugin.VisualShapeReplacePlanner.Position;
import dev.twme.sculpt.plugin.VisualShapeReplacePlanner.Source;

class VisualShapeReplacePlannerTest {

    @Test
    void mergesCrossBlockVisualGeometryInWorldSpace() {
        final VoxelMask first = VoxelMask.builder().set(15, 0, 0).build();
        final VoxelMask spill = VoxelMask.builder().set(0, 0, 0).build();
        final VisualShape shape = VisualShape.builder()
            .addBlock(VisualShape.BlockOffset.ORIGIN, first)
            .addBlock(new VisualShape.BlockOffset(1, 0, 0), spill)
            .build();

        final VisualShapeReplacePlanner.Plan plan = VisualShapeReplacePlanner.plan(
            new Bounds(0, 1, 0, 0, 0, 0),
            List.of(new Source(new Position(0, 0, 0), shape),
                Source.fromMask(new Position(1, 0, 0),
                    VoxelMask.builder().set(1, 0, 0).build())));

        assertFalse(plan.outOfBounds());
        assertEquals(2, plan.masks().size());
        assertTrue(plan.masks().get(new Position(1, 0, 0)).occupied(0, 0, 0));
        assertTrue(plan.masks().get(new Position(1, 0, 0)).occupied(1, 0, 0));
        assertEquals(3, plan.generatedLeaves());
    }

    @Test
    void reportsSpillInsteadOfClippingOrEditingOutsideSelection() {
        final VisualShape spilling = VisualShape.builder()
            .addBlock(new VisualShape.BlockOffset(-1, 0, 0), VoxelMask.full())
            .build();

        final VisualShapeReplacePlanner.Plan plan = VisualShapeReplacePlanner.plan(
            new Bounds(0, 0, 0, 0, 0, 0),
            List.of(new Source(new Position(0, 0, 0), spilling)));

        assertTrue(plan.outOfBounds());
        assertEquals(new Position(-1, 0, 0), plan.outOfBoundsPosition());
        assertTrue(plan.masks().isEmpty());
    }

    @Test
    void fullOutputDoesNotConsumeGeneratedLeafBudget() {
        final VisualShapeReplacePlanner.Plan plan = VisualShapeReplacePlanner.plan(
            new Bounds(0, 0, 0, 0, 0, 0),
            List.of(Source.fromMask(new Position(0, 0, 0), VoxelMask.full())));

        assertFalse(plan.outOfBounds());
        assertEquals(0, plan.generatedLeaves());
    }
}
