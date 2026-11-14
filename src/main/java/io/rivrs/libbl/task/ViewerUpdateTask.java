package io.rivrs.libbl.task;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import io.rivrs.libbl.service.ViewerService;
import lombok.RequiredArgsConstructor;

/**
 * Forgets the channels of the players who left and fills in the ones PacketEvents had not opened yet.
 * <p>
 * Runs on the async scheduler: it only looks players up by unique id, which is safe from any thread.
 */
@RequiredArgsConstructor
public class ViewerUpdateTask implements Runnable {

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
