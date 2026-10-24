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
import org.jetbrains.annotations.Nullable;

import com.polaris.polarisUtils.api.PolarisUtilsAPI;
import com.polaris.polarisUtils.api.model.PlayerSnapshot;
import com.polaris.polarisUtils.api.utils.FieldOfView;

import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.model.entities.PacketEntity;
import io.rivrs.libbl.service.EntityService;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.key.Key;

/**
 * Asynchronously computes, for every auto viewable entity, which players should see it.
 * <p>
 * Removing viewers walks each entity's own viewers; adding them only looks, for each player, at the
 * entities of the chunks within simulation distance ({@link EntityService#near}), so the cost grows
 * with what is around players rather than with every entity times every player.
 * <p>
 * The whole check runs off the main thread: player positions come from {@link PlayerSnapshot}s and
 * blocks from the chunk cache of PolarisUtils, no Bukkit world state is touched.
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
        PolarisUtilsAPI api = PolarisUtilsAPI.get();

        long now = System.currentTimeMillis();
        Set<UUID> processed = new HashSet<>();

        // Remove the viewers that went offline, changed world or moved away: only an entity's own
        // viewers are looked at.
        for (PacketEntity entity : this.service.entities()) {
            Key world = this.trackedWorld(api, entity);
            if (world == null)
                continue;

            processed.add(entity.uniqueId());
            if (entity.viewers().isEmpty())
                continue;

            Map<UUID, Long> lastSeen = this.lastInView.computeIfAbsent(entity.uniqueId(), _ -> new ConcurrentHashMap<>());
            for (UUID uuid : entity.viewers()) {
                PlayerSnapshot snapshot = api.snapshot(uuid).orElse(null);
                if (snapshot != null && this.isVisible(api, snapshot, world, entity, lastSeen, now))
                    continue;

                lastSeen.remove(uuid);
                entity.removeViewer(uuid);
            }
        }

        // Add the players that can now see an entity: only the entities of the chunks within
        // simulation distance of each player are looked at.
        int radius = (int) Math.ceil(Math.sqrt(LibBL.ENTITY_SIMULATION_DISTANCE_SQR()) / 16);
        for (PlayerSnapshot snapshot : api.snapshots()) {
            Player viewer = null;
            for (PacketEntity entity : this.service.near(snapshot.world(), (int) Math.floor(snapshot.x()) >> 4, (int) Math.floor(snapshot.z()) >> 4, radius)) {
                if (entity.isViewer(snapshot.uniqueId()))
                    continue;

                Key world = this.trackedWorld(api, entity);
                if (world == null)
                    continue;

                Map<UUID, Long> lastSeen = this.lastInView.computeIfAbsent(entity.uniqueId(), _ -> new ConcurrentHashMap<>());
                if (!this.isVisible(api, snapshot, world, entity, lastSeen, now))
                    continue;

                if (viewer == null)
                    viewer = Bukkit.getPlayer(snapshot.uniqueId());
                if (viewer == null)
                    break;
                entity.addViewer(viewer);
            }
        }

        // Drop the tracking data of the entities that are gone
        this.lastInView.keySet().retainAll(processed);
    }

    /**
     * The world of an entity the task manages (alive, auto viewable, in a loaded chunk), or
     * {@code null} for one it leaves alone.
     */
    private @Nullable Key trackedWorld(PolarisUtilsAPI api, PacketEntity entity) {
        if (!entity.alive()
            || !entity.autoViewable())
            return null;

        Location location = entity.location();
        if (location == null
            || !location.isWorldLoaded())
            return null;

        Key world = location.getWorld().key();
        return api.isChunkLoaded(world, location.getX(), location.getZ()) ? world : null;
    }

    private boolean isVisible(PolarisUtilsAPI api,
                              PlayerSnapshot snapshot,
                              Key world,
                              PacketEntity entity,
                              Map<UUID, Long> lastSeen,
                              long now) {
        if (!snapshot.isInWorld(world))
            return false;

        Location location = entity.location();
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        if (snapshot.distanceSquared(x, y, z) >= LibBL.ENTITY_SIMULATION_DISTANCE_SQR())
            return false;

        if (!LibBL.FIELD_OF_VIEW_ENABLED() || !entity.fieldOfViewCulled())
            return true;

        double centerY = y + LibBL.FIELD_OF_VIEW_ENTITY_OFFSET();
        boolean inView = FieldOfView.isInFieldOfView(snapshot, x, centerY, z, LibBL.FIELD_OF_VIEW_COS_HALF_ANGLE(), LibBL.FIELD_OF_VIEW_ENTITY_RADIUS())
                         && (!LibBL.LINE_OF_SIGHT_ENABLED()
                             || api.hasLineOfSight(world, snapshot.x(), snapshot.eyeY(), snapshot.z(), x, centerY, z, LibBL.LINE_OF_SIGHT_MAX_DISTANCE()));

        if (inView) {
            lastSeen.put(snapshot.uniqueId(), now);
            return true;
        }

        // Keep the entity spawned during the grace period
        Long last = lastSeen.get(snapshot.uniqueId());
        return last != null && now - last <= LibBL.FIELD_OF_VIEW_GRACE_PERIOD();
    }
}
