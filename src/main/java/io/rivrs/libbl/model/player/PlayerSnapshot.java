package io.rivrs.libbl.model.player;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.key.Key;

/**
 * Immutable, thread safe view of a player position &amp; orientation.
 * <p>
 * Instances are built on the main thread by the {@link io.rivrs.libbl.task.PlayerSnapshotTask}
 * and consumed by asynchronous tasks, so that no Bukkit state has to be touched off the main thread.
 */
public record PlayerSnapshot(UUID uniqueId,
                             Key world,
                             double x,
                             double y,
                             double z,
                             double eyeY,
                             float yaw,
                             float pitch,
                             double directionX,
                             double directionY,
                             double directionZ,
                             long timestamp) {

    public static PlayerSnapshot of(Player player) {
        Location location = player.getLocation();

        float yaw = location.getYaw();
        float pitch = location.getPitch();

        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);

        return new PlayerSnapshot(
                player.getUniqueId(),
                location.getWorld().key(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getY() + player.getEyeHeight(),
                yaw,
                pitch,
                -horizontal * Math.sin(yawRadians),
                -Math.sin(pitchRadians),
                horizontal * Math.cos(yawRadians),
                System.currentTimeMillis()
        );
    }

    public boolean isInWorld(Key worldKey) {
        return this.world.equals(worldKey);
    }

    /**
     * Squared distance between the player feet and the given position.
     */
    public double distanceSquared(double x, double y, double z) {
        double dx = this.x - x;
        double dy = this.y - y;
        double dz = this.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Squared distance between the player eyes and the given position.
     */
    public double eyeDistanceSquared(double x, double y, double z) {
        double dx = this.x - x;
        double dy = this.eyeY - y;
        double dz = this.z - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
