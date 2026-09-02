package io.rivrs.libbl.task;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.model.entities.PacketEntity;
import io.rivrs.libbl.model.player.PlayerSnapshot;
import io.rivrs.libbl.service.EntityService;
import io.rivrs.libbl.service.ViewerService;
import io.rivrs.libbl.service.WorldService;
import io.rivrs.libbl.utils.FieldOfView;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.key.Key;

/**
 * Asynchronously computes, for every auto viewable entity, which players should see it.
 * <p>
 * The whole check runs off the main thread: player positions come from {@link PlayerSnapshot}s and
 * blocks from the chunk snapshots held by the {@link WorldService}, no Bukkit world state is touched.
 */
@RequiredArgsConstructor
public class EntityVisibilityTask extends BukkitRunnable {

    private final LibBL plugin;
    private final EntityService service;

    /**
     * Last time, per entity, each viewer had the entity in its field of view. Used to keep an entity
     * spawned for a short while after it left the view cone, avoiding a flicker when the player turns around.
     */
    private final Map<UUID, Map<UUID, Long>> lastInView = new ConcurrentHashMap<>();

    @Override
    public void run() {
        ViewerService viewerService = this.plugin.viewerService();
        WorldService worldService = this.plugin.worldService();

        long now = System.currentTimeMillis();
        Set<UUID> processed = new HashSet<>();

        for (PacketEntity entity : this.service.entities()) {
            if (!entity.alive()
                || !entity.autoViewable())
                continue;

            Location location = entity.location();
            if (location == null
                || !location.isWorldLoaded())
                continue;

            Key world = location.getWorld().key();
            if (!worldService.isChunkLoaded(world, location.getX(), location.getZ()))
                continue;

            processed.add(entity.uniqueId());
            Map<UUID, Long> lastSeen = this.lastInView.computeIfAbsent(entity.uniqueId(), _ -> new ConcurrentHashMap<>());

            // Remove the viewers that went offline, changed world or moved away
            for (UUID uuid : entity.viewers()) {
                PlayerSnapshot snapshot = viewerService.snapshots().get(uuid);
                if (snapshot != null && this.isVisible(worldService, snapshot, world, location, lastSeen, now))
                    continue;

                lastSeen.remove(uuid);
                entity.removeViewer(uuid);
            }

            // Add the players that can now see the entity
            for (PlayerSnapshot snapshot : viewerService.playerSnapshots()) {
                if (entity.isViewer(snapshot.uniqueId())
                    || !this.isVisible(worldService, snapshot, world, location, lastSeen, now))
                    continue;

                Player viewer = Bukkit.getPlayer(snapshot.uniqueId());
                if (viewer != null)
                    entity.addViewer(viewer);
            }
        }

        // Drop the tracking data of the entities that are gone
        this.lastInView.keySet().retainAll(processed);
    }

    private boolean isVisible(WorldService worldService,
                              PlayerSnapshot snapshot,
                              Key world,
                              Location location,
                              Map<UUID, Long> lastSeen,
                              long now) {
        if (!snapshot.isInWorld(world))
            return false;

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        if (snapshot.distanceSquared(x, y, z) >= LibBL.ENTITY_SIMULATION_DISTANCE_SQR())
            return false;

        if (!LibBL.FIELD_OF_VIEW_ENABLED())
            return true;

        double centerY = y + LibBL.FIELD_OF_VIEW_ENTITY_OFFSET();
        boolean inView = FieldOfView.isInFieldOfView(snapshot, x, centerY, z, LibBL.FIELD_OF_VIEW_COS_HALF_ANGLE(), LibBL.FIELD_OF_VIEW_ENTITY_RADIUS())
                         && (!LibBL.LINE_OF_SIGHT_ENABLED()
                             || worldService.hasLineOfSight(world, snapshot.x(), snapshot.eyeY(), snapshot.z(), x, centerY, z, LibBL.LINE_OF_SIGHT_MAX_DISTANCE()));

        if (inView) {
            lastSeen.put(snapshot.uniqueId(), now);
            return true;
        }

        // Keep the entity spawned during the grace period
        Long last = lastSeen.get(snapshot.uniqueId());
        return last != null && now - last <= LibBL.FIELD_OF_VIEW_GRACE_PERIOD();
    }
}
