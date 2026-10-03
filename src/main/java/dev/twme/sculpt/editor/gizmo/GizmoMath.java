package dev.twme.sculpt.editor.gizmo;

import org.joml.Vector3d;

/**
 * Ray and plane math for picking and dragging gizmo handles.
 *
 * <p>All methods work in world space with {@link Vector3d}. The patterns are
 * the standard ones: closest points between a ray and a segment for handle
 * picking, and a ray/plane intersection for dragging. Every method is written
 * to fail loudly rather than return a plausible but wrong number, so a
 * degenerate case (parallel ray, handle behind the player) reports "no hit"
 * instead of a hit at infinity.</p>
 */
public final class GizmoMath {

    /** Dot products smaller than this count as parallel. */
    private static final double PARALLEL_EPSILON = 1e-9;

    private GizmoMath() {
    }

    /**
     * The distance along {@code ray} at which it passes closest to the
     * segment {@code from}-{@code to}, or {@code -1} when it misses.
     *
     * <p>A hit requires the closest approach to happen in front of the ray
     * origin, between the segment's ends, and within {@code radius}.</p>
     */
    public static double raySegment(
            final Vector3d rayOrigin,
            final Vector3d rayDirection,
            final Vector3d from,
            final Vector3d to,
            final double radius) {
        final Vector3d axis = new Vector3d(to).sub(from);
        final double axisLength = axis.length();
        if (axisLength < PARALLEL_EPSILON) {
            return rayPoint(rayOrigin, rayDirection, from, radius);
        }
        axis.div(axisLength);

        final double rayDotAxis = rayDirection.dot(axis);
        final double denominator = 1.0 - rayDotAxis * rayDotAxis;
        if (denominator < PARALLEL_EPSILON) return -1;

        final Vector3d toRayOrigin = new Vector3d(rayOrigin).sub(from);
        final double distanceAlongRay = (rayDotAxis * toRayOrigin.dot(axis)
            - toRayOrigin.dot(rayDirection)) / denominator;
        if (distanceAlongRay < 0) return -1;

        final double distanceAlongAxis = toRayOrigin
            .fma(distanceAlongRay, rayDirection, new Vector3d()).dot(axis);
        if (distanceAlongAxis < 0 || distanceAlongAxis > axisLength) return -1;

        final Vector3d closestOnRay = new Vector3d(rayOrigin)
            .fma(distanceAlongRay, rayDirection);
        final Vector3d closestOnAxis = new Vector3d(from).fma(distanceAlongAxis, axis);
        if (closestOnRay.distance(closestOnAxis) > radius) return -1;
        return distanceAlongRay;
    }

    /** The distance along {@code ray} at which it passes within {@code radius} of a point. */
    public static double rayPoint(
            final Vector3d rayOrigin,
            final Vector3d rayDirection,
            final Vector3d point,
            final double radius) {
        final Vector3d offset = new Vector3d(point).sub(rayOrigin);
        final double along = offset.dot(rayDirection);
        if (along < 0) return -1;
        final Vector3d closest = new Vector3d(rayOrigin).fma(along, rayDirection);
        return closest.distance(point) > radius ? -1 : along;
    }

    /**
     * Where {@code ray} meets the plane through {@code planePoint} with
     * normal {@code planeNormal}, or {@code null} when the ray is parallel or
     * the plane is behind the ray origin.
     */
    public static Vector3d rayPlane(
            final Vector3d rayOrigin,
            final Vector3d rayDirection,
            final Vector3d planePoint,
            final Vector3d planeNormal) {
        final double denominator = rayDirection.dot(planeNormal);
        if (Math.abs(denominator) < PARALLEL_EPSILON) return null;
        final double distance = new Vector3d(planePoint).sub(rayOrigin).dot(planeNormal) / denominator;
        if (distance < 0) return null;
        return new Vector3d(rayOrigin).fma(distance, rayDirection);
    }

    /**
     * The signed angle from {@code from} to {@code to} around {@code axis},
     * in radians, positive when counter-clockwise seen from the axis tip.
     */
    public static double signedAngle(final Vector3d from, final Vector3d to, final Vector3d axis) {
        final Vector3d a = new Vector3d(from).normalize();
        final Vector3d b = new Vector3d(to).normalize();
        final double cross = new Vector3d(a).cross(b).dot(axis);
        final double dot = a.dot(b);
        return Math.atan2(cross, dot);
    }

    /** The projection of {@code vector} onto {@code axis}. */
    public static Vector3d project(final Vector3d vector, final Vector3d axis) {
        final double length = axis.length();
        if (length < PARALLEL_EPSILON) return new Vector3d();
        final Vector3d unit = new Vector3d(axis).div(length);
        return unit.mul(vector.dot(unit));
    }

    /** The component of {@code vector} perpendicular to {@code axis}. */
    public static Vector3d reject(final Vector3d vector, final Vector3d axis) {
        return new Vector3d(vector).sub(project(vector, axis));
    }

    /** Rounds {@code value} to the nearest multiple of {@code step}. */
    public static double snap(final double value, final double step) {
        if (step <= 0) return value;
        return Math.round(value / step) * step;
    }
}
