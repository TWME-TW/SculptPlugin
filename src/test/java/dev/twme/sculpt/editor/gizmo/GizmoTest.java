package dev.twme.sculpt.editor.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.editor.VoxelBox;
import dev.twme.sculpt.editor.VoxelRotation;

/**
 * Picking and dragging are pure functions of an eye ray, so they are tested
 * here without a server: the same eye and direction a player would produce.
 */
class GizmoTest {

    /** A two-block cube, so the pivot sits at (1, 1, 1) in blocks. */
    private static final VoxelBox SELECTION = new VoxelBox(0, 0, 0, 32, 32, 32);
    private static final Vector3d PIVOT = SELECTION.center();

    private static Vector3d aim(final double dx, final double dy, final double dz) {
        return new Vector3d(dx, dy, dz).normalize();
    }

    /** The direction from an eye position toward a world point. */
    private static Vector3d toward(final Vector3d eye, final Vector3d point) {
        return new Vector3d(point).sub(eye).normalize();
    }

    @Test
    void pivotIsTheSelectionCenterInBlocks() {
        assertEquals(1.0, PIVOT.x, 1e-9);
        assertEquals(1.0, PIVOT.y, 1e-9);
        assertEquals(1.0, PIVOT.z, 1e-9);
    }

    @Test
    void pickingTheNearestHandleAlongTheRay() {
        // Looking north down the Z axis: the +Z arrow points at the camera,
        // and its mirror cube on the far side is further away.
        final Gizmo gizmo = new Gizmo("g.");
        final Vector3d eye = new Vector3d(1, 1, 5);
        gizmo.update(PIVOT, eye, aim(0, 0, -1));
        assertEquals(GizmoHandle.MOVE_Z, gizmo.hovered(),
            "the nearest handle on the ray wins, not the first one tested");
    }

    @Test
    void missingEveryHandlePicksNothing() {
        final Gizmo gizmo = new Gizmo("g.");
        gizmo.update(PIVOT, new Vector3d(1, 1, 5), aim(0, 0, 1));
        assertNull(gizmo.hovered(), "a ray that leaves the gizmo hits nothing");
    }

    @Test
    void handlesAreOutOfReachWhenThePlayerIsTooFar() {
        final Gizmo gizmo = new Gizmo("g.");
        gizmo.update(PIVOT, new Vector3d(1, 1, 45), aim(0, 0, -1));
        assertNull(gizmo.hovered(), "handles beyond the reach limit are not pickable");
    }

    @Test
    void eachHandleIsPickedWhenTheRayPassesThroughIt() {
        // The ray must pass within the handle's radius of its geometry, which
        // is how a player aims: at the handle, not at the pivot.
        assertEquals(GizmoHandle.MOVE_X, pick(new Vector3d(5, 1.1, 1.05), new Vector3d(1.9, 1, 1)));
        assertEquals(GizmoHandle.MOVE_Y, pick(new Vector3d(1.05, 5, 1.025), new Vector3d(1, 1.9, 1)));
        assertEquals(GizmoHandle.MOVE_Z, pick(new Vector3d(1.05, 1.1, 5), new Vector3d(1, 1, 1.9)));
        assertEquals(GizmoHandle.MOVE_XZ, pick(new Vector3d(2.3, 4, 2.3), new Vector3d(1.3, 1, 1.3)));
        assertEquals(GizmoHandle.ROTATE_Y, pick(new Vector3d(1, 4, 1.001), new Vector3d(2, 1, 1)));
        assertEquals(GizmoHandle.MIRROR_X, pick(new Vector3d(1, 4, 1.001), new Vector3d(0.175, 1, 1)));
    }

    /** The handle a player aiming from {@code eye} at {@code target} picks. */
    private static GizmoHandle pick(final Vector3d eye, final Vector3d target) {
        final Gizmo gizmo = new Gizmo("g.");
        gizmo.update(PIVOT, eye, toward(eye, target));
        return gizmo.hovered();
    }

    @Test
    void draggingTheMoveHandleTranslatesOneVoxelPerVoxelMoved() {
        final Gizmo gizmo = new Gizmo("g.");
        final Vector3d eye = new Vector3d(1.05, 5, 1.025);
        final Vector3d look = toward(eye, new Vector3d(1, 1.9, 1));
        gizmo.update(PIVOT, eye, look);
        assertEquals(GizmoHandle.MOVE_Y, gizmo.hovered());
        assertTrue(gizmo.beginDrag(PIVOT, eye, look), "a hovered handle can be grabbed");

        // Sweeping the aim one block further up the handle slides the
        // selection up one block, and nothing leaks into the other axes.
        gizmo.update(PIVOT, eye, toward(eye, new Vector3d(1, 2.9, 1)));
        assertEquals(16, gizmo.offsetY(), "one block of drag is sixteen voxels");
        assertEquals(0, gizmo.offsetX());
        assertEquals(0, gizmo.offsetZ());
        gizmo.update(PIVOT, eye, toward(eye, new Vector3d(1, 3.9, 1)));
        assertEquals(32, gizmo.offsetY(), "the offset tracks the total drag");
    }

    @Test
    void draggingOnlyReactsToTheHandlesOwnAxis() {
        final Gizmo gizmo = new Gizmo("g.");
        final Vector3d eye = new Vector3d(1.05, 5, 1.025);
        final Vector3d look = toward(eye, new Vector3d(1, 1.9, 1));
        gizmo.update(PIVOT, eye, look);
        assertTrue(gizmo.beginDrag(PIVOT, eye, look));

        // Turning the head is what drives the drag, so the selection may
        // travel along the handle, but never off it.
        for (double dz = -1.5; dz <= 1.51; dz += 0.5) {
            gizmo.update(PIVOT, eye, toward(eye, new Vector3d(1, 1.9, 1 + dz)));
            assertEquals(0, gizmo.offsetX(),
                "an axis drag never leaks into the other axes");
            assertEquals(0, gizmo.offsetZ(),
                "an axis drag never leaks into the other axes");
        }
    }

    @Test
    void offsetsSnapToWholeVoxels() {
        final Gizmo gizmo = new Gizmo("g.");
        final Vector3d eye = new Vector3d(1.05, 5, 1.025);
        final Vector3d look = toward(eye, new Vector3d(1, 1.9, 1));
        gizmo.update(PIVOT, eye, look);
        gizmo.beginDrag(PIVOT, eye, look);
        // A whole number of voxels is what keeps a cell from being split.
        for (double y = 4.2; y < 9; y += 0.13) {
            gizmo.update(PIVOT, new Vector3d(1.05, y, 1.025), look);
            assertEquals(0, Math.floorMod(gizmo.offsetY(), 1),
                "an offset is always a whole number of voxels");
        }
    }

    @Test
    void angleStepsCycleCoarsestFirstAndReachFreeRotation() {
        final Gizmo gizmo = new Gizmo("g.");
        assertEquals(15, gizmo.angleStep());
        gizmo.adjustAngleStep(1);
        assertEquals(5, gizmo.angleStep());
        gizmo.adjustAngleStep(1);
        assertEquals(1, gizmo.angleStep());
        gizmo.adjustAngleStep(1);
        assertEquals(0, gizmo.angleStep(), "zero means free rotation");
        gizmo.adjustAngleStep(1);
        assertEquals(90, gizmo.angleStep(), "the steps wrap around");
        gizmo.adjustAngleStep(-1);
        assertEquals(0, gizmo.angleStep(), "scrolling back reaches free rotation");
    }

    @Test
    void resetClearsThePendingTransform() {
        final Gizmo gizmo = new Gizmo("g.");
        final Vector3d eye = new Vector3d(1.05, 5, 1.025);
        final Vector3d look = toward(eye, new Vector3d(1, 1.9, 1));
        gizmo.update(PIVOT, eye, look);
        gizmo.beginDrag(PIVOT, eye, look);
        gizmo.update(PIVOT, new Vector3d(1.05, 7, 1.025), look);
        assertTrue(gizmo.isChanged(), "a drag produces a change");
        gizmo.reset();
        assertFalse(gizmo.isChanged(), "reset discards the change");
        assertFalse(gizmo.isDragging(), "reset ends the drag");
        assertEquals(0, gizmo.offsetY());
    }

    @Test
    void rotatingTheRingTurnsTheSelectionAroundItsCenter() {
        final Gizmo gizmo = new Gizmo("g.");
        final Vector3d eye = new Vector3d(1, 4, 1.001);
        gizmo.update(PIVOT, eye, toward(eye, new Vector3d(2, 1, 1)));
        assertEquals(GizmoHandle.ROTATE_Y, gizmo.hovered(), "the ring is pickable");
        assertTrue(gizmo.beginDrag(PIVOT, eye, toward(eye, new Vector3d(2, 1, 1))));

        // Sweep the aim around the ring; the angle accumulates and stays on
        // the 15 degree step.
        for (int degrees = 15; degrees <= 90; degrees += 15) {
            final double radians = Math.toRadians(degrees);
            final Vector3d target = new Vector3d(
                PIVOT.x + Math.cos(radians), PIVOT.y, PIVOT.z + Math.sin(radians));
            gizmo.update(PIVOT, eye, toward(eye, target));
            assertEquals(0, Math.floorMod(Math.round(Math.toDegrees(gizmo.angle())), 15),
                "the angle snaps to the current step");
        }
        assertTrue(Math.abs(gizmo.angle()) > 0, "sweeping the ring rotates the selection");
        assertEquals(0, gizmo.offsetX(), "a rotation does not translate");
        assertEquals(0, gizmo.offsetZ(), "a rotation does not translate");
        // The rotation pivot is the selection center in voxels, which is the
        // same point the gizmo draws at, expressed in blocks.
        final VoxelRotation rotation = gizmo.rotation(SELECTION);
        assertEquals(16.0, rotation.pivotX(), 1e-9);
        assertEquals(16.0, rotation.pivotZ(), 1e-9);
        assertEquals(PIVOT.x, rotation.pivotX() / 16.0, 1e-9);
    }

    @Test
    void anAxisPointingAtTheCameraIsStillGrabbable() {
        // The +X arrow points straight at the camera, so the drag plane is
        // edge-on to the ray: the handle must still be grabbable, and it must
        // take hold as soon as the view tilts even slightly off the axis.
        final Gizmo gizmo = new Gizmo("g.");
        final Vector3d eye = new Vector3d(5, 1, 1);
        gizmo.update(PIVOT, eye, aim(-1, 0, 0));
        assertEquals(GizmoHandle.MOVE_X, gizmo.hovered());
        assertTrue(gizmo.beginDrag(PIVOT, eye, aim(-1, 0, 0)),
            "an edge-on handle can still be grabbed");

        // The first usable hit becomes the anchor, so the drag takes hold on
        // the update after the view tilts off the axis.
        final Vector3d tilted = new Vector3d(5, 1.5, 1);
        final Vector3d look = toward(tilted, PIVOT);
        gizmo.update(PIVOT, tilted, look);
        final Vector3d pulled = new Vector3d(5, 2.0, 1);
        gizmo.update(PIVOT, pulled, look);
        assertTrue(gizmo.isChanged(), "the drag starts moving once the view tilts");
        assertEquals(0, gizmo.offsetY(), "an X handle does not move the selection up");
        assertEquals(0, gizmo.offsetZ(), "an X handle does not move the selection north");
    }

    @Test
    void theDrawnPivotFollowsThePendingMove() {
        final Gizmo gizmo = new Gizmo("g.");
        // Nothing pending: the handles sit on the selection center.
        assertEquals(PIVOT.x, gizmo.drawnPivot(SELECTION).x, 1e-9);

        // A one-block move east carries the handles with it, so the gizmo
        // shows where the selection is going.
        final Vector3d eye = new Vector3d(1.05, 5, 1.025);
        final Vector3d look = toward(eye, new Vector3d(1, 1.9, 1));
        gizmo.update(PIVOT, eye, look);
        gizmo.beginDrag(PIVOT, eye, look);
        gizmo.update(PIVOT, new Vector3d(1.05, 6, 1.025), look);

        final Vector3d drawn = gizmo.drawnPivot(SELECTION);
        assertEquals(16, gizmo.offsetY(), "the drag moved one block up");
        assertEquals(PIVOT.y + 1.0, drawn.y, 1e-9, "the handles follow the move");
        assertEquals(PIVOT.x, drawn.x, 1e-9, "an up move does not shift the other axes");
        assertEquals(PIVOT.z, drawn.z, 1e-9);
    }

    @Test
    void aMovingPivotDoesNotFeedBackIntoTheDrag() {
        // The drawn pivot follows the offset. If the drag measured against
        // that pivot, each tick's plane would move with it and the offset
        // would compound instead of tracking the view.
        final Gizmo gizmo = new Gizmo("g.");
        final Vector3d eye = new Vector3d(1.05, 5, 1.025);
        final Vector3d look = toward(eye, new Vector3d(1, 1.9, 1));
        gizmo.update(PIVOT, eye, look);
        gizmo.beginDrag(PIVOT, eye, look);

        // One block of drag: the offset must settle and stay put when the
        // view is unchanged, however many ticks run.
        final Vector3d pulled = new Vector3d(1.05, 6, 1.025);
        gizmo.update(PIVOT, pulled, look);
        final long settled = gizmo.offsetY();
        assertEquals(16, settled);
        for (int tick = 0; tick < 20; tick++) {
            // Each tick re-derives the pivot the way the tool does, so the
            // anchor is what keeps this from compounding.
            gizmo.update(gizmo.drawnPivot(SELECTION), pulled, look);
            assertEquals(settled, gizmo.offsetY(),
                "an unchanged view must not keep moving the selection");
        }
    }

    @Test
    void degreesAreNormalizedForDisplay() {
        final Gizmo gizmo = new Gizmo("g.");
        assertEquals(0.0, gizmo.degrees(), 1e-9);
        assertFalse(gizmo.isRotating(), "nothing is being rotated yet");
    }
}
