package io.rivrs.libbl.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.github.retrooper.packetevents.PacketEvents;

import io.rivrs.libbl.LibBL;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.rivrs.libbl.task.ViewerUpdateTask;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ViewerService {

    private final LibBL plugin;
    private final Map<UUID, Object> playerChannels = new ConcurrentHashMap<>();
    private ViewerUpdateTask viewerUpdateTask;
    private ScheduledTask viewerUpdateHandle;

    public void init() {
        this.viewerUpdateTask = new ViewerUpdateTask(this);
        // Every 10 seconds, as the 200 ticks it used to run at
        this.viewerUpdateHandle = Bukkit.getAsyncScheduler().runAtFixedRate(this.plugin, _ -> this.viewerUpdateTask.run(), 50L, 10_000L, TimeUnit.MILLISECONDS);
    }

    public Object getPlayerChannel(UUID uuid) {
        return this.playerChannels.get(uuid);
    }

    public List<Object> getPlayerChannels(Set<UUID> uuids) {
        return uuids.stream()
                .map(this.playerChannels::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public void registerPlayerChannel(UUID uuid, Object channel) {
        this.playerChannels.put(uuid, channel);
    }

    public void registerPlayer(Player player) {
        this.registerPlayer(player.getUniqueId());
    }

    public void registerPlayer(UUID uuid) {
        Object channel = PacketEvents.getAPI().getProtocolManager().getChannel(uuid);
        registerPlayerChannel(uuid, channel);
    }

    public void registerPlayers(Collection<? extends Player> players) {
        for (Player player : players) {
            registerPlayer(player);
        }
    }

    public void unregisterPlayerChannel(UUID uuid) {
        this.playerChannels.remove(uuid);
    }

    public void shutdown() {
        if (this.viewerUpdateHandle != null)
            this.viewerUpdateHandle.cancel();
        this.playerChannels.clear();
    }
}
