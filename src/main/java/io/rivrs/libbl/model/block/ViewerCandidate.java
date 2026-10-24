package io.rivrs.libbl.model.block;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/// A player that may become the viewer of a block, with its position read once.
///
/// [World#getPlayers()] copies its player list on every call and [Player#getLocation()] allocates a
/// [Location] on every call, so a bulk update gathers its candidates once rather than redoing both
/// for each of its blocks.
///
/// @param x the x position of the player when the candidates were gathered
/// @param y the y position of the player when the candidates were gathered
/// @param z the z position of the player when the candidates were gathered
public record ViewerCandidate(UUID uniqueId, Player player, double x, double y, double z) {

    public static ViewerCandidate of(Player player) {
        Location location = player.getLocation();
        return new ViewerCandidate(player.getUniqueId(), player, location.getX(), location.getY(), location.getZ());
    }

    /// Squared distance to a block position, without touching a single Bukkit object.
    public double distanceSquared(int x, int y, int z) {
        double dx = this.x - x;
        double dy = this.y - y;
        double dz = this.z - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
