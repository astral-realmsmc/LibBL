package io.rivrs.libbl.task;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import io.rivrs.libbl.service.ViewerService;
import lombok.RequiredArgsConstructor;

/**
 * Refreshes, from the main thread, the thread safe view of every online player so that the
 * asynchronous visibility checks never have to touch Bukkit state.
 */
@RequiredArgsConstructor
public class PlayerSnapshotTask extends BukkitRunnable {

    private final ViewerService service;

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.service.updateSnapshot(player);
        }

        // Drop the snapshots of the players that are gone
        for (UUID uuid : this.service.snapshots().keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline())
                this.service.snapshots().remove(uuid);
        }
    }
}
