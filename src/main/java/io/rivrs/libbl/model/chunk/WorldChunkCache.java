package io.rivrs.libbl.model.chunk;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import io.rivrs.libbl.utils.ThreadSafeLong2ObjectMap;
import lombok.Getter;

/**
 * Snapshot of the loaded chunks of a world, safe to read from any thread.
 */
@Getter
public class WorldChunkCache {

    private final ThreadSafeLong2ObjectMap<ChunkSnapshot> chunks = new ThreadSafeLong2ObjectMap<>();
    /**
     * Chunks whose snapshot is outdated, mapped to the tick they were marked at.
     */
    private final Map<Long, Integer> dirtyChunks = new ConcurrentHashMap<>();
    private final UUID worldId;
    private final int minHeight;
    private final int maxHeight;

    public WorldChunkCache(World world) {
        this.worldId = world.getUID();
        this.minHeight = world.getMinHeight();
        this.maxHeight = world.getMaxHeight();
    }

    public void register(Chunk chunk) {
        long key = chunk.getChunkKey();

        // Biomes & lighting are not needed, skipping them keeps the snapshots as small as possible
        this.chunks.put(key, chunk.getChunkSnapshot(false, false, false));
        this.dirtyChunks.remove(key);
    }

    public void unregister(Chunk chunk) {
        long key = chunk.getChunkKey();

        this.chunks.remove(key);
        this.dirtyChunks.remove(key);
    }

    public void unregister(long chunkKey) {
        this.chunks.remove(chunkKey);
        this.dirtyChunks.remove(chunkKey);
    }

    /**
     * Flags the snapshot of the given chunk as outdated, it will be rebuilt by the
     * {@link io.rivrs.libbl.task.ChunkRefreshTask}. Safe to call from any thread.
     *
     * @param tick current server tick, the refresh only happens on a later tick so that the
     *             block change actually applied before the snapshot is rebuilt
     * @return {@code true} when the chunk is cached and has been marked
     */
    public boolean markDirty(long chunkKey, int tick) {
        if (this.chunks.get(chunkKey) == null)
            return false;

        this.dirtyChunks.putIfAbsent(chunkKey, tick);
        return true;
    }

    public boolean isDirty(long chunkKey) {
        return this.dirtyChunks.containsKey(chunkKey);
    }

    public @Nullable ChunkSnapshot chunk(int x, int z) {
        return this.chunks.get(Chunk.getChunkKey(x, z));
    }

    /**
     * Block type at the given <strong>world</strong> coordinates, or {@code null} when the chunk
     * is not cached or the position is outside of the world boundaries.
     */
    public @Nullable Material blockType(int x, int y, int z) {
        if (y < this.minHeight || y >= this.maxHeight)
            return null;

        ChunkSnapshot snapshot = this.chunk(x >> 4, z >> 4);
        if (snapshot == null)
            return null;

        return snapshot.getBlockData(x & 15, y, z & 15).getMaterial();
    }

    public void clear() {
        this.chunks.clear();
        this.dirtyChunks.clear();
    }
}
