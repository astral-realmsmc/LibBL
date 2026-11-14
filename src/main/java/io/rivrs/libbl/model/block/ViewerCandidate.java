package io.rivrs.libbl.model.block;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.polaris.polarisUtils.api.PolarisUtilsAPI;
import com.polaris.polarisUtils.api.model.PlayerSnapshot;

import net.kyori.adventure.key.Key;

/// A player that may become the viewer of a block, with its position read once.
///
/// [World#getPlayers()] copies its player list on every call and [Player#getLocation()] allocates a
/// [Location] on every call, so a bulk update gathers its candidates once rather than redoing both
/// for each of its blocks.
///
/// Gather them with [#gather(World)]: it reads the PolarisUtils player snapshots, safe from any
/// thread, where [#of(Player)] reads the live location and on Folia only works from the thread
/// owning the player.
///
/// @param x the x position of the player when the candidates were gathered
/// @param y the y position of the player when the candidates were gathered
/// @param z the z position of the player when the candidates were gathered
public record ViewerCandidate(UUID uniqueId, Player player, double x, double y, double z) {

    /// Every online player of the world, positioned as of their last snapshot. Safe from any thread.
    public static List<ViewerCandidate> gather(@Nullable World world) {
        if (world == null)
            return List.of();

        Key key = world.key();
        List<ViewerCandidate> candidates = new ArrayList<>();
        for (PlayerSnapshot snapshot : PolarisUtilsAPI.get().snapshots()) {
            if (!snapshot.isInWorld(key))
                continue;

            Player player = Bukkit.getPlayer(snapshot.uniqueId());
            if (player != null)
                candidates.add(of(snapshot, player));
        }
        return candidates;
    }

    public static ViewerCandidate of(PlayerSnapshot snapshot, Player player) {
        return new ViewerCandidate(snapshot.uniqueId(), player, snapshot.x(), snapshot.y(), snapshot.z());
    }

    /// Reads the live location of the player: on Folia, call it from the thread owning the player,
    /// or use [#gather(World)].
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
