package io.rivrs.libbl.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange.EncodedBlock;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.event.block.FakeBlockRemoveEvent;
import io.rivrs.libbl.model.block.BlockUpdateReport;
import io.rivrs.libbl.model.block.FakeBlock;
import io.rivrs.libbl.model.block.ViewerCandidate;
import io.rivrs.libbl.utils.BlockPos;
import io.rivrs.libbl.utils.ThreadSafeLong2ObjectMap;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class BlockService {

    private final LibBL plugin;

    private final Map<UUID, FakeBlock> fakeBlocks = new ConcurrentHashMap<>();
    /// Position to block, per world, keyed by the packed position of the block rather than by a
    /// [Location] - hashing one walks a weak reference and six floating point fields.
    private final Map<String, ThreadSafeLong2ObjectMap<UUID>> positionUUIDMap = new ConcurrentHashMap<>();

    private final Map<String, ThreadSafeLong2ObjectMap<Set<UUID>>> worldChunkUUIDMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<BlockData, Integer> dataStateCache = new ConcurrentHashMap<>();

    BukkitRunnable cleanupTask = new BukkitRunnable() {
        @Override
        public void run() {
            cleanUp();
        }
    };

    public void init() {
        this.cleanupTask.runTaskTimerAsynchronously(this.plugin, 0, 200L);
    }

    public void shutdown() {
        this.fakeBlocks.clear();
    }

    public void register(FakeBlock fakeBlock) {
        this.fakeBlocks.put(fakeBlock.uniqueID(), fakeBlock);

        long chunkKey = getChunkKeyFromPosition(fakeBlock.blockX(), fakeBlock.blockZ());
        this.worldChunkUUIDMap.computeIfAbsent(fakeBlock.worldName(), key -> new ThreadSafeLong2ObjectMap<>())
                .computeIfAbsent(chunkKey, key -> ConcurrentHashMap.newKeySet())
                .add(fakeBlock.uniqueID());

        // Claim the spot and learn who held it in one go
        UUID existingUUID = this.positions(fakeBlock.worldName()).put(fakeBlock.positionKey(), fakeBlock.uniqueID());
        if (existingUUID != null) {
            FakeBlock existingBlock = this.fakeBlocks.remove(existingUUID);
            if (existingBlock != null) {
                replace(existingBlock);
            }
        }
    }

    public void unregister(FakeBlock fakeBlock) {
        fakeBlock.remove();
        this.forget(fakeBlock);
    }

    /// Drops a block that another one is taking the place of.
    ///
    /// Unlike [#unregister(FakeBlock)] this sends nothing : the block moving in covers the very same
    /// position and pushes its own state right after, so a removal packet would only be overwritten.
    /// Pasting a schematic over an older one used to send one such wasted packet per block.
    private void replace(FakeBlock fakeBlock) {
        if (fakeBlock.placed()) {
            new FakeBlockRemoveEvent(fakeBlock).callEvent();
            fakeBlock.markRemoved();
        }
        // Its registry entry and its position already belong to the block moving in
        this.forgetChunk(fakeBlock);
    }

    private void forget(FakeBlock fakeBlock) {
        this.fakeBlocks.remove(fakeBlock.uniqueID());
        ThreadSafeLong2ObjectMap<UUID> positions = this.positionUUIDMap.get(fakeBlock.worldName());
        if (positions != null) {
            positions.remove(fakeBlock.positionKey());
        }
        this.forgetChunk(fakeBlock);
    }

    private ThreadSafeLong2ObjectMap<UUID> positions(String worldName) {
        return this.positionUUIDMap.computeIfAbsent(worldName, key -> new ThreadSafeLong2ObjectMap<>());
    }

    private void forgetChunk(FakeBlock fakeBlock) {
        ThreadSafeLong2ObjectMap<Set<UUID>> worldBlocks = this.worldChunkUUIDMap.get(fakeBlock.worldName());
        if (worldBlocks != null) {
            long chunkKey = getChunkKeyFromPosition(fakeBlock.blockX(), fakeBlock.blockZ());
            Set<UUID> chunkBlocks = worldBlocks.get(chunkKey);
            if (chunkBlocks != null) {
                // The set is mutated in place, there is nothing to put back
                chunkBlocks.remove(fakeBlock.uniqueID());
            }
        }
    }

    /// Registers every given block, for blocks built with the non registering constructor.
    public void registerAll(Collection<FakeBlock> blocks) {
        for (FakeBlock block : blocks)
            this.register(block);
    }

    /// How many fake blocks are registered, without copying the registry the way [#fakeBlocks()] does.
    public int size() {
        return this.fakeBlocks.size();
    }

    public Optional<FakeBlock> findByUUID(UUID uuid) {
        FakeBlock fakeBlock = this.fakeBlocks.get(uuid);
        return Optional.ofNullable(fakeBlock);
    }

    public Optional<FakeBlock> findByLocation(Location location) {
        World world = location.getWorld();
        return world == null
                ? Optional.empty()
                : findByWorldNameAndPosition(world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Optional<FakeBlock> findByWorldNameAndPosition(String worldName, int x, int y, int z) {
        ThreadSafeLong2ObjectMap<UUID> positions = this.positionUUIDMap.get(worldName);
        if (positions == null) {
            return Optional.empty();
        }
        UUID fakeBlockUUID = positions.get(BlockPos.pack(x, y, z));
        if (fakeBlockUUID == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.fakeBlocks.get(fakeBlockUUID));
    }

    public List<FakeBlock> findByChunk(int chunkX, int chunkZ, World world) {
        List<FakeBlock> blocksInChunk = new ArrayList<>();

        long chunkKey = Chunk.getChunkKey(chunkX, chunkZ);
        ThreadSafeLong2ObjectMap<Set<UUID>> worldBlocks = this.worldChunkUUIDMap.get(world.getName());
        if (worldBlocks != null) {
            Set<UUID> chunkBlockUUIDs = worldBlocks.get(chunkKey);
            if (chunkBlockUUIDs != null) {
                for (UUID uuid : chunkBlockUUIDs) {
                    FakeBlock block = this.fakeBlocks.get(uuid);
                    if (block != null && block.placed()) {
                        blocksInChunk.add(block);
                    }
                }
            }
        }
        return blocksInChunk;
    }

    public List<FakeBlock> findByWorld(World world) {
        return findByWorldName(world.getName());
    }

    public List<FakeBlock> findByWorldName(String worldName) {
        List<FakeBlock> blocksInWorld = new ArrayList<>();
        ThreadSafeLong2ObjectMap<Set<UUID>> worldBlockUUIDs = this.worldChunkUUIDMap.get(worldName);
        if (worldBlockUUIDs == null)
            return blocksInWorld;

        worldBlockUUIDs.forEach((key, value) -> {
            for (UUID uuid : value) {
                FakeBlock block = this.fakeBlocks.get(uuid);
                if (block != null) {
                    blocksInWorld.add(block);
                }
            }
        });
        return blocksInWorld;
    }

    public boolean existsAtPosition(Location location) {
        return findByLocation(location).isPresent();
    }

    public boolean existsAtPosition(Vector3i position, World world) {
        return findByWorldNameAndPosition(world.getName(), position.x, position.y, position.z).isPresent();
    }

    public boolean existsAtWorld(World world) {
        return this.worldChunkUUIDMap.containsKey(world.getName());
    }

    @Unmodifiable
    public Map<UUID, FakeBlock> fakeBlocks() {
        return Map.copyOf(this.fakeBlocks);
    }

    /// Builds the smallest set of packets updating every given block to the state it currently holds.
    ///
    /// Blocks are grouped per chunk section - a 16x16x16 cube, the only thing a multi block change
    /// packet can address - so a schematic spread over a dozen sections costs a dozen packets rather
    /// than one per block. A section holding a single block is sent as a plain block change, which
    /// is smaller than a multi block change carrying one entry.
    ///
    /// The blocks are read as they are : place them with [#placeAll(Collection)] or remove them with
    /// [#removeAll(Collection)] rather than calling this directly, unless you send the packets yourself.
    public List<PacketWrapper<?>> buildChangePackets(Collection<FakeBlock> blocks) {
        Map<Section, List<FakeBlock>> sections = new LinkedHashMap<>();
        for (FakeBlock block : blocks) {
            Section section = new Section(block.worldName(), block.blockX() >> 4, block.blockY() >> 4, block.blockZ() >> 4);
            sections.computeIfAbsent(section, key -> new ArrayList<>())
                    .add(block);
        }

        List<PacketWrapper<?>> packets = new ArrayList<>(sections.size());
        sections.forEach((section, sectionBlocks) -> {
            if (sectionBlocks.size() == 1) {
                FakeBlock block = sectionBlocks.getFirst();
                packets.add(new WrapperPlayServerBlockChange(
                        new Vector3i(block.blockX(), block.blockY(), block.blockZ()), block.stateID()));
                return;
            }

            EncodedBlock[] encodedBlocks = new EncodedBlock[sectionBlocks.size()];
            for (int index = 0; index < encodedBlocks.length; index++) {
                FakeBlock block = sectionBlocks.get(index);
                // The encoded block takes world coordinates, it packs the local ones itself
                encodedBlocks[index] = new EncodedBlock(block.stateID(), block.blockX(), block.blockY(), block.blockZ());
            }

            packets.add(new WrapperPlayServerMultiBlockChange(
                    new Vector3i(section.x(), section.y(), section.z()),
                    true,
                    encodedBlocks
            ));
        });

        return packets;
    }

    /// Places every given block at once, batching the packets of the blocks sharing a chunk section.
    ///
    /// Behaves exactly like calling [FakeBlock#place()] on each of them - same events, same viewer
    /// detection - it only sends far fewer packets.
    ///
    /// @return what the update cost, ignore it unless you are profiling
    public BlockUpdateReport placeAll(Collection<FakeBlock> blocks) {
        return this.placeAll(blocks, true);
    }

    /// Places every given block at once, batching the packets of the blocks sharing a chunk section.
    ///
    /// @param fireEvents whether [io.rivrs.libbl.event.block.FakeBlockPlaceEvent] and
    ///                   [io.rivrs.libbl.event.block.FakeBlockAddViewerEvent] are fired for every
    ///                   block. Leave it on unless you know nothing listens to them : a paste of a
    ///                   hundred thousand blocks fires two hundred thousand events, and dropping
    ///                   them changes nothing to what the viewers end up seeing.
    /// @return what the update cost, ignore it unless you are profiling
    public BlockUpdateReport placeAll(Collection<FakeBlock> blocks, boolean fireEvents) {
        long start = System.nanoTime();

        // Gathered once for the whole update, see ViewerCandidate
        Map<String, List<ViewerCandidate>> candidates = new HashMap<>();

        Map<UUID, List<FakeBlock>> byViewer = new HashMap<>();
        int placed = 0;
        for (FakeBlock block : blocks) {
            List<ViewerCandidate> worldCandidates = candidates.computeIfAbsent(block.worldName(), BlockService::gatherCandidates);
            if (!block.preparePlace(worldCandidates, fireEvents))
                continue;

            placed++;
            for (UUID viewer : block.viewersView())
                byViewer.computeIfAbsent(viewer, key -> new ArrayList<>())
                        .add(block);
        }

        return this.sendToViewers(byViewer, placed, System.nanoTime() - start);
    }

    private static List<ViewerCandidate> gatherCandidates(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null)
            return List.of();

        List<ViewerCandidate> candidates = new ArrayList<>();
        for (Player player : world.getPlayers())
            candidates.add(ViewerCandidate.of(player));

        return candidates;
    }

    /// Removes every given block at once, batching the packets of the blocks sharing a chunk section.
    public BlockUpdateReport removeAll(Collection<FakeBlock> blocks) {
        return this.removeAll(blocks, Material.AIR.createBlockData());
    }

    /// Removes every given block at once, batching the packets of the blocks sharing a chunk section.
    ///
    /// @param blockData the state the viewers see in place of the removed blocks
    /// @return what the update cost, ignore it unless you are profiling
    public BlockUpdateReport removeAll(Collection<FakeBlock> blocks, BlockData blockData) {
        long start = System.nanoTime();

        Map<UUID, List<FakeBlock>> byViewer = new HashMap<>();
        List<FakeBlock> removed = new ArrayList<>();
        for (FakeBlock block : blocks) {
            if (!block.prepareRemove(blockData))
                continue;

            removed.add(block);
            for (UUID viewer : block.viewersView())
                byViewer.computeIfAbsent(viewer, key -> new ArrayList<>())
                        .add(block);
        }

        BlockUpdateReport report = this.sendToViewers(byViewer, removed.size(), System.nanoTime() - start);
        removed.forEach(FakeBlock::markRemoved);
        return report;
    }

    /// Sends the current state of every given block to a single viewer, batched the same way
    /// [#placeAll(Collection)] does.
    ///
    /// Handy to show an already placed set of blocks to a player joining its viewers.
    public void sendAll(Player viewer, Collection<FakeBlock> blocks) {
        this.sendAll(this.plugin.viewerService().getPlayerChannel(viewer.getUniqueId()), blocks);
    }

    /// Sends the current state of every given block to a single channel, batched the same way
    /// [#placeAll(Collection)] does.
    public void sendAll(@Nullable Object channel, Collection<FakeBlock> blocks) {
        if (channel == null || blocks.isEmpty())
            return;

        this.sendBundled(channel, this.buildChangePackets(blocks));
    }

    private BlockUpdateReport sendToViewers(Map<UUID, List<FakeBlock>> byViewer, int blocks, long prepareNanos) {
        if (byViewer.isEmpty())
            return new BlockUpdateReport(blocks, 0, 0, prepareNanos, 0L, 0L);

        ViewerService viewerService = this.plugin.viewerService();
        int viewers = 0;
        int packets = 0;
        long buildNanos = 0L;
        long sendNanos = 0L;

        for (Map.Entry<UUID, List<FakeBlock>> entry : byViewer.entrySet()) {
            Object channel = viewerService.getPlayerChannel(entry.getKey());
            if (channel == null)
                continue;

            long start = System.nanoTime();
            List<PacketWrapper<?>> viewerPackets = this.buildChangePackets(entry.getValue());
            long built = System.nanoTime();
            this.sendBundled(channel, viewerPackets);

            buildNanos += built - start;
            sendNanos += System.nanoTime() - built;
            viewers++;
            packets += viewerPackets.size();
        }

        return new BlockUpdateReport(blocks, viewers, packets, prepareNanos, buildNanos, sendNanos);
    }

    /// Sends a whole set of packets down a channel in one go.
    ///
    /// Clients from 1.19.4 onwards understand bundles : everything sitting between the two delimiters
    /// is applied in the same client tick, so a schematic pops up at once instead of section by
    /// section over several frames. Older clients simply get the packets back to back.
    ///
    /// Either way only the last packet flushes the channel, the rest are written into it first.
    private void sendBundled(Object channel, List<PacketWrapper<?>> packets) {
        if (packets.isEmpty())
            return;

        ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();
        if (packets.size() > 1 && supportsBundles(protocolManager.getClientVersion(channel))) {
            protocolManager.writePacket(channel, new WrapperPlayServerBundle());
            for (PacketWrapper<?> packet : packets)
                protocolManager.writePacket(channel, packet);

            // The closing delimiter flushes everything written above
            protocolManager.sendPacket(channel, new WrapperPlayServerBundle());
            return;
        }

        for (int index = 0; index < packets.size() - 1; index++)
            protocolManager.writePacket(channel, packets.get(index));
        protocolManager.sendPacket(channel, packets.getLast());
    }

    private static boolean supportsBundles(@Nullable ClientVersion clientVersion) {
        return clientVersion != null && clientVersion.isNewerThanOrEquals(ClientVersion.V_1_19_4);
    }

    /// The chunk section - a 16x16x16 cube - a multi block change packet addresses.
    private record Section(String world, int x, int y, int z) {
    }

    private void cleanUp() {
        worldChunkUUIDMap.keySet().forEach(this::cleanUpWorld);
    }

    public void cleanUpWorld(String worldName) {
        if (!this.worldChunkUUIDMap.containsKey(worldName))
            return;
        ThreadSafeLong2ObjectMap<Set<UUID>> chunkMap = this.worldChunkUUIDMap.get(worldName);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            chunkMap.forEach((chunkPos, uuidList) -> {
                for (UUID uuid : uuidList) {
                    FakeBlock fakeBlock = this.fakeBlocks.get(uuid);
                    if (fakeBlock != null) {
                        unregister(fakeBlock);
                    }
                }
            });
            worldChunkUUIDMap.remove(worldName);
            positionUUIDMap.remove(worldName);
        }
    }

    @Nullable
    public Integer getDataState(BlockData blockData) {
        return this.dataStateCache.computeIfAbsent(blockData, bd -> {
            try {
                return SpigotConversionUtil.fromBukkitBlockData(bd).getGlobalId();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to compute data state for block data: " + bd + e.getMessage());
                return null;
            }
        });
    }
    
    public void addBlockDataToCache(BlockData blockData) {
        if( this.dataStateCache.containsKey(blockData)) {
            return;
        }
        int stateID;
        try {
            stateID = SpigotConversionUtil.fromBukkitBlockData(blockData).getGlobalId();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to compute data state for block data: " + blockData + e.getMessage());
            return;
        }
        this.dataStateCache.put(blockData, stateID);
    }


    private long getChunkKeyFromPosition(int x, int z) {
        return Chunk.getChunkKey(x>>4, z>>4);
    }
}
