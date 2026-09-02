package io.rivrs.libbl.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import io.rivrs.libbl.LibBL;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class WorldListener implements Listener {

    private final LibBL plugin;

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        this.plugin.worldService().registerChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        this.plugin.worldService().unregisterChunk(event.getChunk());
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        this.plugin.worldService().unregisterWorld(event.getWorld().key());
    }
}
