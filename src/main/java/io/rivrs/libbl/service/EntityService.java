package io.rivrs.libbl.service;

import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.model.entities.PacketEntity;
import io.rivrs.libbl.task.EntityVisibilityTask;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.key.Key;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Every registered packet entity, by id, by unique id, and by the chunk it is in, so that finding
 * one and finding those near a player cost no scan of all of them.
 * <p>
 * Thread safe: entities register from any thread, and the visibility task reads asynchronously.
 */
@RequiredArgsConstructor
public class EntityService {

    private final LibBL plugin;
    private final Map<Integer, PacketEntity> byId = new ConcurrentHashMap<>();
    private final Map<UUID, PacketEntity> byUniqueId = new ConcurrentHashMap<>();
    /**
     * By world, then chunk key ({@link Chunk#getChunkKey(int, int)}).
     */
    private final Map<Key, Map<Long, Set<PacketEntity>>> byChunk = new ConcurrentHashMap<>();
    /**
     * The chunk each entity is indexed in, to move it when it changes chunk.
     */
    private final Map<Integer, IndexedChunk> indexedIn = new ConcurrentHashMap<>();
    private EntityVisibilityTask visibilityTask;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> updateLoopFuture;

    public void init() {
        this.visibilityTask = new EntityVisibilityTask(this.plugin, this);
        this.visibilityTask.runTaskTimerAsynchronously(this.plugin, 0, 5);
        updateLoopFuture = executor.scheduleAtFixedRate(() -> {
            try {
                byId.values().forEach(entity -> entity.processScheduledActions(System.currentTimeMillis()));
            } catch (Exception e) {
                this.plugin.getSLF4JLogger().error("Exception thrown in skill update loop", e);
            }
        }, 0, LibBL.ENTITY_UPDATE_RATE(), TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        this.byId.clear();
        this.byUniqueId.clear();
        this.byChunk.clear();
        this.indexedIn.clear();
        this.visibilityTask.cancel();
    }

    public void register(PacketEntity entity) {
        this.byId.put(entity.id(), entity);
        this.byUniqueId.put(entity.uniqueId(), entity);
        this.index(entity);
    }

    public void unregister(PacketEntity entity) {
        if (!this.byId.remove(entity.id(), entity))
            return;
        this.byUniqueId.remove(entity.uniqueId(), entity);
        this.deindex(entity);
    }

    public void unregister(UUID uniqueId) {
        PacketEntity entity = this.byUniqueId.get(uniqueId);
        if (entity != null)
            this.unregister(entity);
    }

    public void unregister(int entityId) {
        PacketEntity entity = this.byId.get(entityId);
        if (entity != null)
            this.unregister(entity);
    }

    /**
     * The entity moved: indexed again if it changed chunk. Called by {@link PacketEntity}'s
     * teleports.
     */
    public void moved(PacketEntity entity) {
        if (this.byId.get(entity.id()) != entity)
            return;

        synchronized (entity) {
            IndexedChunk now = IndexedChunk.of(entity.location());
            if (!Objects.equals(now, this.indexedIn.get(entity.id()))) {
                this.deindex(entity);
                this.index(entity);
            }
        }
    }

    /**
     * The entities of the chunks within {@code radius} chunks of a chunk.
     */
    public List<PacketEntity> near(Key world, int chunkX, int chunkZ, int radius) {
        Map<Long, Set<PacketEntity>> chunks = this.byChunk.get(world);
        if (chunks == null)
            return List.of();

        List<PacketEntity> entities = new ArrayList<>();
        for (int x = chunkX - radius; x <= chunkX + radius; x++)
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                Set<PacketEntity> chunk = chunks.get(Chunk.getChunkKey(x, z));
                if (chunk != null)
                    entities.addAll(chunk);
            }
        return entities;
    }

    public Optional<PacketEntity> findByUniqueId(UUID uniqueId) {
        return Optional.ofNullable(this.byUniqueId.get(uniqueId));
    }

    public <T extends PacketEntity> Optional<T> findByUniqueId(UUID uniqueId, Class<T> type) {
        return this.findByUniqueId(uniqueId)
                .filter(type::isInstance)
                .map(type::cast);
    }

    public Optional<PacketEntity> findById(int entityId) {
        return Optional.ofNullable(this.byId.get(entityId));
    }

    public <T extends PacketEntity> Optional<T> findById(int entityId, Class<T> type) {
        return this.findById(entityId)
                .filter(type::isInstance)
                .map(type::cast);
    }

    public Optional<PacketEntity> findByLocation(Location location) {
        return this.byId.values().stream()
                .filter(entity -> entity.location().equals(location))
                .findFirst();
    }

    public <T extends PacketEntity> Optional<T> findByLocation(Location location, Class<T> type) {
        return this.byId.values().stream()
                .filter(entity -> entity.location().equals(location))
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst();
    }

    public List<PacketEntity> findByWorld(World world) {
        return this.byId.values().stream()
                .filter(entity -> entity.location().getWorld().equals(world))
                .toList();
    }

    public <T extends PacketEntity> List<T> findByWorld(World world, Class<T> type) {
        return this.byId.values().stream()
                .filter(entity -> entity.location().getWorld().equals(world))
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    public <T extends PacketEntity> List<T> findByType(Class<T> type) {
        return this.byId.values().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    /**
     * A snapshot of every registered entity, in no particular order.
     */
    @Unmodifiable
    public List<PacketEntity> entities() {
        return List.copyOf(this.byId.values());
    }

    private void index(PacketEntity entity) {
        IndexedChunk chunk = IndexedChunk.of(entity.location());
        if (chunk == null)
            return;
        this.byChunk.computeIfAbsent(chunk.world(), _ -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunk.key(), _ -> ConcurrentHashMap.newKeySet())
                .add(entity);
        this.indexedIn.put(entity.id(), chunk);
    }

    private void deindex(PacketEntity entity) {
        IndexedChunk chunk = this.indexedIn.remove(entity.id());
        if (chunk == null)
            return;
        Map<Long, Set<PacketEntity>> chunks = this.byChunk.get(chunk.world());
        if (chunks == null)
            return;
        // Emptied sets are dropped so that unloaded areas cost nothing.
        chunks.computeIfPresent(chunk.key(), (_, entities) -> {
            entities.remove(entity);
            return entities.isEmpty() ? null : entities;
        });
    }

    private record IndexedChunk(Key world, long key) {

        static @Nullable IndexedChunk of(@Nullable Location location) {
            if (location == null || location.getWorld() == null)
                return null;
            return new IndexedChunk(location.getWorld().key(), Chunk.getChunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4));
        }
    }
}
