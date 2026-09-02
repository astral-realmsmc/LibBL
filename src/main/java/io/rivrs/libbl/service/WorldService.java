package io.rivrs.libbl.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;

import io.rivrs.libbl.model.chunk.WorldChunkCache;
import it.unimi.dsi.fastutil.longs.Long2ObjectArrayMap;
import lombok.Synchronized;
import net.kyori.adventure.key.Key;

public class WorldService {

    private final Map<Key, WorldChunkCache> worlds = new ConcurrentHashMap<>();

    @Synchronized
    public void registerChunk(Chunk chunk) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("ChunkService.register must be called from the main thread.");

        this.worlds.computeIfAbsent(chunk.getWorld().key(), _ -> new WorldChunkCache(new Long2ObjectArrayMap<>()))
                .register(chunk);
    }

    @Synchronized
    public void unregisterChunk(Chunk chunk) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("ChunkService.unregister must be called from the main thread.");

        WorldChunkCache cache = this.worlds.get(chunk.getWorld().key());
        if (cache != null)
            cache.unregister(chunk);
    }

    public Optional<ChunkSnapshot> chunkSnapshot(Key worldKey, int x, int z) {
        return this.world(worldKey)
                .map(world -> world.chunk(x, z));
    }

    public Optional<WorldChunkCache> world(Key worldKey) {
        return Optional.ofNullable(this.worlds.get(worldKey));
    }

    public void unregisterWorld(Key worldKey) {
        this.worlds.remove(worldKey);
    }

}
