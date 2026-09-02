package io.rivrs.libbl.task;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import io.rivrs.libbl.service.ViewerService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ViewerUpdateTask extends BukkitRunnable {

    private final ViewerService service;

    @Override
    public void run() {
        this.service.playerChannels().forEach((uuid, channel) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                this.service.unregisterPlayerChannel(uuid);
                return;
            }
            if (channel == null) {
                this.service.registerPlayer(uuid);
            }
        });
    }
}
