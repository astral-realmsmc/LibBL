package io.rivrs.libbl.utils;

import io.rivrs.libbl.model.player.PlayerSnapshot;
import lombok.experimental.UtilityClass;

/**
 * Thread safe field of view math, working exclusively on {@link PlayerSnapshot}s so that
 * it can be evaluated from any thread.
 */
@UtilityClass
public final class FieldOfView {

    /**
     * Cosine of the half aperture of a cone, for the given aperture in degrees.
     * Pre-compute it once and feed it to {@link #isInFieldOfView(PlayerSnapshot, double, double, double, double)}.
     */
    public static double cosHalfAngle(double angleDegrees) {
        return Math.cos(Math.toRadians(Math.clamp(angleDegrees, 0.0D, 360.0D) / 2.0D));
    }

    /**
     * Checks whether the given point lies inside the player view cone.
     *
     * @param cosHalfAngle cosine of the half aperture, see {@link #cosHalfAngle(double)}
     */
    public static boolean isInFieldOfView(PlayerSnapshot viewer, double x, double y, double z, double cosHalfAngle) {
        double dx = x - viewer.x();
        double dy = y - viewer.eyeY();
        double dz = z - viewer.z();

        double lengthSquared = dx * dx + dy * dy + dz * dz;
        if (lengthSquared < 1.0E-4D) // The point is inside the player, always visible
            return true;

        double dot = dx * viewer.directionX() + dy * viewer.directionY() + dz * viewer.directionZ();
        if (dot >= 0.0D)
            return cosHalfAngle <= 0.0D || dot * dot >= cosHalfAngle * cosHalfAngle * lengthSquared;

        // Aperture greater than 180°, the cone also covers a part of the player back
        return cosHalfAngle < 0.0D && dot * dot <= cosHalfAngle * cosHalfAngle * lengthSquared;
    }

    /**
     * Checks whether a sphere of the given radius, centered on the given point, intersects the player view cone.
     * <p>
     * This is the check to use for entities: an entity whose center is slightly off-screen may still have a
     * visible part, and using the center alone makes entities pop in and out at the edge of the screen.
     *
     * @param cosHalfAngle cosine of the half aperture, see {@link #cosHalfAngle(double)}
     * @param radius       radius of the entity bounding sphere
     */
    public static boolean isInFieldOfView(PlayerSnapshot viewer, double x, double y, double z, double cosHalfAngle, double radius) {
        if (radius <= 0.0D)
            return isInFieldOfView(viewer, x, y, z, cosHalfAngle);

        double dx = x - viewer.x();
        double dy = y - viewer.eyeY();
        double dz = z - viewer.z();

        double lengthSquared = dx * dx + dy * dy + dz * dz;
        if (lengthSquared <= radius * radius) // The player is inside the entity bounding sphere
            return true;

        double length = Math.sqrt(lengthSquared);
        double dot = (dx * viewer.directionX() + dy * viewer.directionY() + dz * viewer.directionZ()) / length;
        double angle = Math.acos(Math.clamp(dot, -1.0D, 1.0D));

        // Angular radius of the sphere as seen from the player eyes
        double halfAngle = Math.acos(Math.clamp(cosHalfAngle, -1.0D, 1.0D));
        return angle <= halfAngle + Math.asin(Math.min(1.0D, radius / length));
    }
}
