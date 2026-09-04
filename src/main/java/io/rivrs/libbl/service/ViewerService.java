package io.rivrs.libbl.service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Unmodifiable;

import com.github.retrooper.packetevents.PacketEvents;

import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.model.player.PlayerSnapshot;
import io.rivrs.libbl.task.PlayerSnapshotTask;
import io.rivrs.libbl.task.ViewerUpdateTask;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ViewerService {

    private final LibBL plugin;
    private final Map<UUID, Object> playerChannels = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
    private ViewerUpdateTask viewerUpdateTask;
    private PlayerSnapshotTask playerSnapshotTask;

    public void init() {
        this.viewerUpdateTask = new ViewerUpdateTask(this);
        this.viewerUpdateTask.runTaskTimerAsynchronously(this.plugin, 0, 200L);

        // Snapshots have to be taken from the main thread
        this.playerSnapshotTask = new PlayerSnapshotTask(this);
        this.playerSnapshotTask.runTaskTimer(this.plugin, 0, 1L);
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
        UUID uuid = player.getUniqueId();
        this.registerPlayer(uuid);
        this.updateSnapshot(player);
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
        this.snapshots.remove(uuid);
    }

    /**
     * Refreshes the thread safe view of the given player, has to be called from the main thread.
     */
    public void updateSnapshot(Player player) {
        this.snapshots.put(player.getUniqueId(), PlayerSnapshot.of(player));
    }

    public Optional<PlayerSnapshot> snapshot(UUID uuid) {
        return Optional.ofNullable(this.snapshots.get(uuid));
    }

    /**
     * Thread safe view of every online player position &amp; orientation.
     */
    @Unmodifiable
    public Collection<PlayerSnapshot> playerSnapshots() {
        return Collections.unmodifiableCollection(this.snapshots.values());
    }

    public void shutdown() {
        this.viewerUpdateTask.cancel();
        this.playerSnapshotTask.cancel();
        this.playerChannels.clear();
        this.snapshots.clear();
    }
}
