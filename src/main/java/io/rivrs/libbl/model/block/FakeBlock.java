package io.rivrs.libbl.model.block;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.event.block.FakeBlockAddViewerEvent;
import io.rivrs.libbl.event.block.FakeBlockPlaceEvent;
import io.rivrs.libbl.event.block.FakeBlockRemoveEvent;
import io.rivrs.libbl.event.block.FakeBlockRemoveViewerEvent;
import io.rivrs.libbl.model.ViewerHolder;
import io.rivrs.libbl.utils.BlockPos;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.logging.log4j.util.InternalApi;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Unmodifiable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Getter
public class FakeBlock implements ViewerHolder {

    /// Roughly what a single fake block retains in the heap, in bytes : the object itself, its viewer
    /// array, its sequence map, its block data, plus the entries it takes in the
    /// [io.rivrs.libbl.service.BlockService] registry.
    ///
    /// Measured on a 64 bit JVM with compressed oops and no viewer, count around 16 more bytes per
    /// viewer. Useful to size a cache of already built blocks - note that keeping the
    /// [io.rivrs.libbl.model.schematic.Schematic] instead only costs about 4 bytes per block.
    public static final int APPROXIMATE_HEAP_SIZE = 226;

    private static volatile BlockData air;

    /// Copy on write, and shared while empty so that building a block allocates nothing for it.
    @Getter(AccessLevel.NONE)
    private volatile UUID[] viewers = ViewerArray.EMPTY;

    private final UUID uniqueID;
    private final BlockData blockData;
    private final int oldStateID;
    private final World world;
    /// The block position packed into a long, see [BlockPos].
    private final long positionKey;
    private final String worldName;
    private boolean autoViewable = true;
    private int stateID;
    private boolean placed;

    /// Created on demand, most fake blocks never get dug at and an empty map per block adds up fast.
    @Getter(AccessLevel.NONE)
    private volatile Map<UUID, Integer> sequenceId;

    public FakeBlock(BlockData blockData, Location location) {
        this(blockData, location, true);
    }

    /// Builds a block straight from values the caller has already worked out, skipping everything the
    /// public constructor redoes for each block : the protocol id of the state, the flooring of the
    /// location and the name of its world.
    ///
    /// Meant for bulk creation - [io.rivrs.libbl.model.schematic.Schematic#buildFakeBlocks] uses it -
    /// where a whole paste shares the same world and only a few hundred distinct states. The block is
    /// not registered, hand it to
    /// [io.rivrs.libbl.service.BlockService#registerAll(java.util.Collection)] afterwards.
    ///
    /// @param location must already sit on whole block coordinates, it is not normalised
    @InternalApi
    public FakeBlock(BlockData blockData, int stateID, World world, String worldName, long positionKey) {
        this.blockData = blockData;
        this.stateID = stateID;
        this.oldStateID = stateID;
        this.world = world;
        this.worldName = worldName;
        this.positionKey = positionKey;
        this.uniqueID = randomUniqueID();
    }

    public int blockX() {
        return BlockPos.x(this.positionKey);
    }

    public int blockY() {
        return BlockPos.y(this.positionKey);
    }

    public int blockZ() {
        return BlockPos.z(this.positionKey);
    }

    /// The location of the block, built on demand.
    ///
    /// The block only keeps its position packed into a long, so this hands back a fresh [Location]
    /// every time : hold on to the result rather than calling it in a loop, and note that mutating it
    /// no longer moves the block - which it never should have.
    public Location location() {
        return new Location(this.world, this.blockX(), this.blockY(), this.blockZ());
    }

    /// The position of the block, built on demand just like [#location()].
    public Vector3i position() {
        return new Vector3i(this.blockX(), this.blockY(), this.blockZ());
    }

    /// @param register whether the block joins the [io.rivrs.libbl.service.BlockService] straight away.
    ///                 Pass `false` when building a lot of blocks at once and hand them over to
    ///                 [io.rivrs.libbl.service.BlockService#registerAll(java.util.Collection)] after.
    public FakeBlock(BlockData blockData, Location location, boolean register) {
        this.blockData = blockData;
        this.stateID = this.stateID(blockData);
        this.oldStateID = this.stateID;
        this.world = location.getWorld();
        this.worldName = this.world.getName();
        this.positionKey = BlockPos.pack(location.getBlockX(), location.getBlockY(), location.getBlockZ());

        this.uniqueID = randomUniqueID();

        if (register)
            register();
    }


    public void register() {
        LibBL.get().blockService().register(this);
    }

    public void unregister() {
        LibBL.get().blockService().unregister(this);
    }

    public void place() {
        if (!this.preparePlace())
            return;

        PacketWrapper<?> packet = this.buildPlacePacket();
        this.viewersAsChannel().forEach(channel -> this.sendPacket(channel, packet));
    }

    /// Does everything [#place()] does but sending the packets : fires the events, picks the nearby
    /// viewers up and marks the block as alive.
    ///
    /// Only useful to place a lot of blocks at once, in which case
    /// [io.rivrs.libbl.service.BlockService#placeAll(Collection)] batches their packets together.
    /// Use [#place()] for a single block.
    ///
    /// @return whether the block was placed by this call, and its viewers have to be told about it
    @InternalApi
    public boolean preparePlace() {
        World world = this.world;
        List<ViewerCandidate> candidates = new ArrayList<>();
        if (world != null)
            for (Player player : world.getPlayers())
                candidates.add(ViewerCandidate.of(player));

        return this.preparePlace(candidates, true);
    }

    /// @param candidates the players that may see the block, gathered once by the caller rather than
    ///                   once per block
    /// @param fireEvents whether [FakeBlockPlaceEvent] and [FakeBlockAddViewerEvent] are fired. Leave
    ///                   it on unless nothing listens to them - a big paste fires two events per
    ///                   block, which is the cheapest thing to drop when they serve no purpose
    @InternalApi
    public boolean preparePlace(List<ViewerCandidate> candidates, boolean fireEvents) {
        if (this.placed)
            return false;
        if (fireEvents && !new FakeBlockPlaceEvent(this).callEvent())
            return false;

        // Detect nearby players
        if (this.autoViewable)
            for (ViewerCandidate candidate : candidates) {
                if (candidate.distanceSquared(this.blockX(), this.blockY(), this.blockZ()) > LibBL.RENDER_DISTANCE_SQR())
                    continue;
                if (fireEvents
                        && !new FakeBlockAddViewerEvent(this, candidate.player(), FakeBlockAddViewerEvent.Reason.BLOCK_PLACE).callEvent())
                    continue;

                this.addViewerUnchecked(candidate.uniqueId());
            }

        // Mark as alive
        this.placed = true;
        return true;
    }

    /// The live viewer array, so that a bulk update can walk it without copying it for every block.
    /// Never modify what comes back.
    @InternalApi
    public UUID[] viewersView() {
        return this.viewers;
    }

    /// Adds a viewer to the copy on write array, no event, no packet.
    private synchronized boolean addViewerUnchecked(UUID uniqueId) {
        UUID[] updated = ViewerArray.with(this.viewers, uniqueId);
        if (updated == this.viewers)
            return false;

        this.viewers = updated;
        return true;
    }

    private synchronized boolean removeViewerUnchecked(UUID uniqueId) {
        UUID[] updated = ViewerArray.without(this.viewers, uniqueId);
        if (updated == this.viewers)
            return false;

        this.viewers = updated;
        return true;
    }

    /// Does everything [#remove(BlockData)] does but sending the packets : fires the event and swaps
    /// the state for the one the viewers have to see instead.
    ///
    /// The block stays marked as placed so that the packets can still be sent, call [#markRemoved()]
    /// once they are.
    ///
    /// @return whether the block was placed, and its viewers have to be told about the removal
    @InternalApi
    public boolean prepareRemove(BlockData blockData) {
        if (!this.placed)
            return false;

        new FakeBlockRemoveEvent(this).callEvent();
        this.stateID = this.stateID(blockData);
        return true;
    }

    /// Marks a block whose removal packets have been sent as gone, see [#prepareRemove(BlockData)].
    @InternalApi
    public void markRemoved() {
        this.placed = false;
    }

    /// You should not use this. The only purpose of this is to re-place the fakeBlock in case of the break fake block event.
    @InternalApi
    public void silentPlace(Object channel) {
        this.placed = true;
        PacketWrapper<?> packet = this.buildPlacePacket();
        this.sendPacket(channel, packet);
    }

    public void remove(BlockData blockData) {
        if (!this.placed)
            return;

        new FakeBlockRemoveEvent(this).callEvent();

        PacketWrapper<?> packet = this.buildRemovePacket(blockData);
        this.viewersAsChannel().forEach(channel -> this.sendPacket(channel, packet));

        this.placed = false;
    }

    public void remove() {
        // Building the air state goes through the block registry, do not pay for it to bail out below
        if (!this.placed)
            return;

        this.remove(air());
    }

    /// The air state every removal falls back on, built once : `Material.AIR.createBlockData()` walks
    /// the registry, which is far too slow to redo for each block of a schematic being taken down.
    private static BlockData air() {
        BlockData air = FakeBlock.air;
        if (air == null)
            FakeBlock.air = air = Material.AIR.createBlockData();

        return air;
    }

    /// This methode will attempt to put back what the server know should be at the fakeBlock position.
    ///
    /// The methode is way slower / way laggier than using remove(BlockData), use at your own risks !
    public void smartRemove() {
        Location location = this.location();
        if (!location.isWorldLoaded()) {
            remove();
            return;
        }

        CompletableFuture<Chunk> completableFuture = this.world.getChunkAtAsyncUrgently(location);
        completableFuture.thenAcceptAsync(chunk -> {
            // Chunk#getBlock takes coordinates relative to the chunk, not to the world
            Block block = chunk.getBlock(this.blockX() & 15, this.blockY(), this.blockZ() & 15);
            remove(block.getBlockData());
        });
    }

    protected WrapperPlayServerBlockChange buildRemovePacket(BlockData blockData) {
        this.stateID = this.stateID(blockData);
        return new WrapperPlayServerBlockChange(
                this.position(),
                this.stateID
        );
    }

    /// The protocol id of a block state, falling back on air when the state cannot be converted.
    private int stateID(BlockData blockData) {
        Integer stateID = LibBL.get().blockService().getDataState(blockData);
        return stateID == null ? 0 : stateID;
    }

    protected WrapperPlayServerBlockChange buildPlacePacket() {
        return new WrapperPlayServerBlockChange(
                this.position(),
                this.stateID
        );
    }


    @Override
    public void addViewer(Player player) {
        if (this.isViewer(player)
                || !new FakeBlockAddViewerEvent(this, player, FakeBlockAddViewerEvent.Reason.PLUGIN).callEvent())
            return;

        this.addViewerUnchecked(player.getUniqueId());
        this.setSequenceId(player.getUniqueId(), 0);

        PacketWrapper<?> packet = this.buildPlacePacket();
        this.sendPacket(player, packet);
    }

    public void silentAddViewer(Player player) {
        if (this.isViewer(player)
                || !new FakeBlockAddViewerEvent(this, player, FakeBlockAddViewerEvent.Reason.PLUGIN).callEvent())
            return;

        this.addViewerUnchecked(player.getUniqueId());
    }

    @Override
    public void removeViewer(Player viewer) {
        if (!this.isViewer(viewer)
                || !new FakeBlockRemoveViewerEvent(this, viewer, FakeBlockRemoveViewerEvent.Reason.PLUGIN).callEvent())
            return;

        this.removeViewerUnchecked(viewer.getUniqueId());
        Map<UUID, Integer> sequenceIds = this.sequenceId;
        if (sequenceIds != null)
            sequenceIds.remove(viewer.getUniqueId());

        PacketWrapper<?> packet = this.buildPlacePacket();
        this.sendPacket(viewer, packet);

    }

    @Override
    public void removeViewer(UUID uniqueId) {
        Player viewer = Bukkit.getPlayer(uniqueId);
        if (viewer != null) {
            this.removeViewer(viewer);
            return;
        }

        // The player is offline, there is nothing left to send
        this.removeViewerUnchecked(uniqueId);
    }

    @Override
    public boolean isViewer(Player player) {
        return this.isViewer(player.getUniqueId());
    }

    @Override
    public boolean isViewer(UUID uniqueId) {
        return ViewerArray.contains(this.viewers, uniqueId);
    }

    @Override
    public @Unmodifiable Set<UUID> viewers() {
        return Set.of(this.viewers);
    }

    @Override
    public @Unmodifiable List<Object> viewersAsChannel() {
        return LibBL.get().viewerService().getPlayerChannels(this.viewers());
    }

    @Override
    public List<Player> viewersAsPlayer() {
        return viewers().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public void autoViewable(boolean autoViewable) {
        this.autoViewable = autoViewable;
    }

    @Override
    public boolean isAutoViewable() {
        return this.autoViewable;
    }

    public void sendPacket(Object channel, PacketWrapper<?>... packetWrappers) {
        if (!placed)
            return;

        if (channel instanceof Player player) {
            channel = LibBL.get().viewerService().getPlayerChannel(player.getUniqueId());
        }

        ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();

        for (PacketWrapper<?> packetWrapper : packetWrappers) {
            protocolManager.sendPacket(channel, packetWrapper);
        }
    }

    public void sendPacket(PacketWrapper<?>... packetWrappers) {
        if (!placed)
            return;

        ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();

        for (PacketWrapper<?> packetWrapper : packetWrappers) {
            for (Object channel : this.viewersAsChannel()) {
                protocolManager.sendPacket(channel, packetWrapper);
            }
        }
    }

    /// The last dig sequence seen from each viewer, never null but often empty.
    public Map<UUID, Integer> sequenceId() {
        Map<UUID, Integer> sequenceId = this.sequenceId;
        return sequenceId == null ? Map.of() : sequenceId;
    }

    public void setSequenceId(UUID uuid, int sequenceId) {
        this.sequenceIdMap().put(uuid, sequenceId);
    }

    public int getSequenceId(UUID uuid) {
        Map<UUID, Integer> sequenceIds = this.sequenceId;
        return sequenceIds == null ? 0 : sequenceIds.getOrDefault(uuid, 0);
    }

    private Map<UUID, Integer> sequenceIdMap() {
        Map<UUID, Integer> sequenceIds = this.sequenceId;
        if (sequenceIds != null)
            return sequenceIds;

        synchronized (this) {
            if (this.sequenceId == null)
                this.sequenceId = new ConcurrentHashMap<>();
            return this.sequenceId;
        }
    }

    /// A type 4 UUID drawn from the fast thread local RNG rather than the cryptographic one.
    ///
    /// These ids only key the [io.rivrs.libbl.service.BlockService] registry and never reach a client,
    /// while [UUID#randomUUID()] goes through `SecureRandom` and costs about fifteen times more -
    /// which shows when a schematic builds a hundred thousand blocks in one go.
    private static UUID randomUniqueID() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new UUID(
                random.nextLong() & 0xFFFFFFFFFFFF0FFFL | 0x0000000000004000L,
                random.nextLong() & 0x3FFFFFFFFFFFFFFFL | Long.MIN_VALUE
        );
    }
}
