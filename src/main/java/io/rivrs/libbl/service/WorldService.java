package io.rivrs.libbl.service;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.model.chunk.WorldChunkCache;
import io.rivrs.libbl.task.ChunkRefreshTask;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.key.Key;

@RequiredArgsConstructor
public class WorldService {

    private static final int MAX_RAY_STEPS = 512;

    private final LibBL plugin;
    private final Map<Key, WorldChunkCache> worlds = new ConcurrentHashMap<>();
    private ChunkRefreshTask chunkRefreshTask;

    public void init() {
        if (LibBL.CHUNK_REFRESH_INTERVAL() <= 0)
            return;

        // Snapshots have to be rebuilt from the main thread
        this.chunkRefreshTask = new ChunkRefreshTask(this);
        this.chunkRefreshTask.runTaskTimer(this.plugin, LibBL.CHUNK_REFRESH_INTERVAL(), LibBL.CHUNK_REFRESH_INTERVAL());
    }

    public void registerChunk(Chunk chunk) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("WorldService.registerChunk must be called from the main thread.");

        World world = chunk.getWorld();
        this.worlds.computeIfAbsent(world.key(), _ -> new WorldChunkCache(world))
                .register(chunk);
    }

    public void unregisterChunk(Chunk chunk) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("WorldService.unregisterChunk must be called from the main thread.");

        WorldChunkCache cache = this.worlds.get(chunk.getWorld().key());
        if (cache != null)
            cache.unregister(chunk);
    }

    /**
     * Flags the snapshot of the chunk containing the given block as outdated. Safe to call from any thread,
     * the snapshot itself is rebuilt later on by the {@link ChunkRefreshTask}.
     */
    public void markDirty(Block block) {
        this.markDirty(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
    }

    public void markDirty(Chunk chunk) {
        this.markDirty(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    public void markDirty(World world, int chunkX, int chunkZ) {
        WorldChunkCache cache = this.worlds.get(world.key());
        if (cache != null)
            cache.markDirty(Chunk.getChunkKey(chunkX, chunkZ), Bukkit.getCurrentTick());
    }

    /**
     * Rebuilds the snapshots of the chunks that were flagged as outdated, has to be called from the main thread.
     * <p>
     * Chunks marked during the current tick are skipped: block events fire <em>before</em> the block actually
     * changes, so re-snapshotting them right away would cache the old state again.
     *
     * @param limit maximum number of chunks to rebuild, the remaining ones are handled by the next run
     * @return the number of rebuilt snapshots
     */
    public int refreshDirtyChunks(int limit) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("WorldService.refreshDirtyChunks must be called from the main thread.");

        int currentTick = Bukkit.getCurrentTick();
        int refreshed = 0;

        for (WorldChunkCache cache : this.worlds.values()) {
            if (refreshed >= limit)
                break;

            World world = Bukkit.getWorld(cache.worldId());
            Iterator<Map.Entry<Long, Integer>> iterator = cache.dirtyChunks().entrySet().iterator();
            while (iterator.hasNext() && refreshed < limit) {
                Map.Entry<Long, Integer> entry = iterator.next();
                if (entry.getValue() >= currentTick) // Marked during this tick, the block change may not be applied yet
                    continue;

                long key = entry.getKey();
                iterator.remove();

                int x = (int) key;
                int z = (int) (key >> 32);
                if (world == null || !world.isChunkLoaded(x, z)) {
                    cache.unregister(key);
                    continue;
                }

                cache.register(world.getChunkAt(x, z));
                refreshed++;
            }
        }

        return refreshed;
    }

    public Optional<ChunkSnapshot> chunkSnapshot(Key worldKey, int x, int z) {
        return this.world(worldKey)
                .map(world -> world.chunk(x, z));
    }

    /**
     * Whether the chunk containing the given block position is cached, i.e. loaded.
     * Thread safe alternative to {@code Location#isChunkLoaded()}.
     */
    public boolean isChunkLoaded(Key worldKey, double x, double z) {
        WorldChunkCache cache = this.worlds.get(worldKey);
        return cache != null && cache.chunk(floor(x) >> 4, floor(z) >> 4) != null;
    }

    public Optional<WorldChunkCache> world(Key worldKey) {
        return Optional.ofNullable(this.worlds.get(worldKey));
    }

    public void unregisterWorld(Key worldKey) {
        WorldChunkCache cache = this.worlds.remove(worldKey);
        if (cache != null)
            cache.clear();
    }

    public void shutdown() {
        if (this.chunkRefreshTask != null)
            this.chunkRefreshTask.cancel();

        this.worlds.values().forEach(WorldChunkCache::clear);
        this.worlds.clear();
    }

    /**
     * Thread safe line of sight check, walking the cached chunk snapshots with a voxel traversal
     * (Amanatides &amp; Woo) and stopping on the first occluding block.
     * <p>
     * Positions whose chunk is not cached cannot be resolved and are considered visible, so that a
     * missing snapshot never hides an entity.
     *
     * @param maxDistance maximum traced distance, {@code <= 0} to trace the whole segment
     * @return {@code true} when no occluding block stands between both positions
     */
    public boolean hasLineOfSight(Key worldKey,
                                  double fromX, double fromY, double fromZ,
                                  double toX, double toY, double toZ,
                                  double maxDistance) {
        WorldChunkCache cache = this.worlds.get(worldKey);
        if (cache == null)
            return true;

        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;

        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-4D)
            return true;

        double limit = maxDistance > 0.0D ? Math.min(distance, maxDistance) : distance;

        // Normalized direction
        dx /= distance;
        dy /= distance;
        dz /= distance;

        int x = floor(fromX);
        int y = floor(fromY);
        int z = floor(fromZ);
        int endX = floor(toX);
        int endY = floor(toY);
        int endZ = floor(toZ);

        int stepX = Double.compare(dx, 0.0D);
        int stepY = Double.compare(dy, 0.0D);
        int stepZ = Double.compare(dz, 0.0D);

        double deltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dx);
        double deltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dy);
        double deltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dz);

        double nextX = stepX == 0 ? Double.MAX_VALUE : (stepX > 0 ? x + 1 - fromX : fromX - x) * deltaX;
        double nextY = stepY == 0 ? Double.MAX_VALUE : (stepY > 0 ? y + 1 - fromY : fromY - y) * deltaY;
        double nextZ = stepZ == 0 ? Double.MAX_VALUE : (stepZ > 0 ? z + 1 - fromZ : fromZ - z) * deltaZ;

        for (int step = 0; step < MAX_RAY_STEPS; step++) {
            if (x == endX && y == endY && z == endZ)
                return true;

            if (Math.min(nextX, Math.min(nextY, nextZ)) > limit)
                return true;

            if (nextX <= nextY && nextX <= nextZ) {
                x += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                y += stepY;
                nextY += deltaY;
            } else {
                z += stepZ;
                nextZ += deltaZ;
            }

            Material material = cache.blockType(x, y, z);
            if (material == null) // Unknown position, cannot resolve it
                return true;
            if (material.isOccluding())
                return false;
        }

        return true;
    }

    private static int floor(double value) {
        int floor = (int) value;
        return value < floor ? floor - 1 : floor;
    }
}
