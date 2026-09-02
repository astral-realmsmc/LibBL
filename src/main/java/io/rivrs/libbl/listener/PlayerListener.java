package io.rivrs.libbl.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.model.entities.PacketEntity;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class PlayerListener implements Listener {

    private final LibBL plugin;

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        for (PacketEntity entity : this.plugin.entityService().entities()) {
            if (!entity.alive()
                || !entity.autoViewable())
                continue;
            entity.removeViewer(player);
        }
        this.plugin.viewerService().unregisterPlayerChannel(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.plugin.viewerService().registerPlayer(event.getPlayer());
    }

}
