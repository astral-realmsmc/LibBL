package io.rivrs.libbl.model.chunk;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

public record WorldChunkCache(Long2ObjectMap<ChunkSnapshot> chunks) {

    public void register(Chunk chunk) {
        this.chunks.put(chunk.getChunkKey(), chunk.getChunkSnapshot());
    }

    public void unregister(Chunk chunk) {
        this.chunks.remove(chunk.getChunkKey());
    }

    public ChunkSnapshot chunk(int x, int z) {
        return this.chunks.get(Chunk.getChunkKey(x, z));
    }

}
