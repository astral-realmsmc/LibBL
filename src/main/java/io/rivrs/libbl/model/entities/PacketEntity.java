package io.rivrs.libbl.model.entities;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityMetadataProvider;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import com.polaris.polarisUtils.api.PolarisUtilsAPI;
import com.polaris.polarisUtils.api.model.PlayerSnapshot;
import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.event.entity.PacketEntityAddViewerEvent;
import io.rivrs.libbl.event.entity.PacketEntityDespawnEvent;
import io.rivrs.libbl.event.entity.PacketEntityRemoveViewerEvent;
import io.rivrs.libbl.event.entity.PacketEntitySpawnEvent;
import io.rivrs.libbl.model.EntityAnimation;
import io.rivrs.libbl.model.EquipmentType;
import io.rivrs.libbl.model.ViewerHolder;
import io.rivrs.libbl.model.flag.EntityFlags;
import io.rivrs.libbl.model.flag.Flag;
import io.rivrs.libbl.utils.ParticleUtils;
import lombok.AccessLevel;
import lombok.Getter;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.bukkit.inventory.MainHand;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

@Getter
public abstract class PacketEntity implements EntityMetadataProvider, ViewerHolder {

    // Identification
    protected final int id;
    protected final UUID uniqueId;
    protected final EntityType type;
    protected final ItemStack[] equipment = new ItemStack[6];
    protected final Set<UUID> viewers = new CopyOnWriteArraySet<>();
    // Passengers
    protected final Set<PacketEntity> passengersIds = new CopyOnWriteArraySet<>();
    // Vehicle
    protected volatile int vehicleId = -1;
    // Metadata, read by the visibility task while the owner of the entity edits it
    private final Set<Flag> flags = ConcurrentHashMap.newKeySet();
    // Attributes
    protected final List<WrapperPlayServerUpdateAttributes.Property> properties = new CopyOnWriteArrayList<>();
    // Velocity
    protected Vector3d velocity;
    // Data
    /**
     * Never mutated in place once set: a teleport or a rotation swaps in a new copy, so that the
     * asynchronous visibility task always reads a consistent position.
     */
    protected volatile Location location;
    protected int airTicks = 300;
    protected Component customName = Component.empty();
    protected boolean customNameVisible = false;
    protected boolean silent;
    protected boolean gravity = true;
    protected Pose pose = Pose.STANDING;
    protected int frozenTicks = 0;
    // Viewers
    protected volatile boolean autoViewable = true;
    // State
    protected volatile boolean alive;
    /**
     * Whether the visibility task may hide the entity from a player who turned away from it (field
     * of view, line of sight); distance always applies.
     */
    protected volatile boolean fieldOfViewCulled = true;

    // batch(): how deep, and whether a setter asked for the metadata meanwhile
    @Getter(AccessLevel.NONE)
    private int batchDepth;
    @Getter(AccessLevel.NONE)
    private boolean metadataPending;
    // PacketEntityConsumer
    protected Map<Long,Set<Consumer<PacketEntity>>> consumers = new ConcurrentHashMap<>();

    public PacketEntity(EntityType type, Location location) {
        this(SpigotReflectionUtil.generateEntityId(), UUID.randomUUID(), type, location);
    }

    public PacketEntity(UUID uniqueId, EntityType type, Location location) {
        this(SpigotReflectionUtil.generateEntityId(), uniqueId, type, location);
    }

    public PacketEntity(int id, UUID uniqueId, EntityType type, Location location) {
        this.id = id;
        this.uniqueId = uniqueId;
        this.type = type;
        this.location = location;

        this.register();
    }

    public void spawn() {
        if (this.alive
                || !new PacketEntitySpawnEvent(this).callEvent())
            return;

        // Detect nearby players, from their snapshots: spawn() is called from any thread, which on
        // Folia rarely owns the players around, so their live location cannot be read here
        if (this.autoViewable) {
            Location location = this.location;
            Key world = location.getWorld().key();
            for (PlayerSnapshot snapshot : PolarisUtilsAPI.get().snapshots()) {
                if (!snapshot.isInWorld(world)
                        || snapshot.distanceSquared(location.getX(), location.getY(), location.getZ()) > LibBL.ENTITY_SIMULATION_DISTANCE_SQR())
                    continue;

                Player player = Bukkit.getPlayer(snapshot.uniqueId());
                if (player != null
                        && new PacketEntityAddViewerEvent(this, player, PacketEntityAddViewerEvent.Reason.ENTITY_SPAWN).callEvent())
                    this.viewers.add(player.getUniqueId());
            }
        }

        // Mark as alive
        this.alive = true;

        // Send packets
        for (UUID viewerId : this.viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null)
                continue;

            LinkedList<PacketWrapper<?>> packets = this.buildSpawnPackets(viewer);
            for (PacketWrapper<?> packet : packets) {
                this.sendPacket(viewer, packet);
            }
        }
    }

    protected LinkedList<PacketWrapper<?>> buildSpawnPackets(Player player) {
        LinkedList<PacketWrapper<?>> packets = new LinkedList<>();

        packets.add(this.buildSpawnPacket());
        packets.add(this.buildMetadataPacket());
        if (this.hasEquipment())
            packets.add(this.buildEquipmentPacket());
        // Passengers packet has to be sent after the entity spawn packet, otherwise it won't work
        Bukkit.getAsyncScheduler().runNow(LibBL.get(), _ -> {
            if (this.hasPassengers()) {
                this.sendPacket(player, this.buildSetPassengersPacket());
            }
            if(!this.properties.isEmpty()){
                this.sendPacket(player, this.buildAttributePacket());
            }
            if(this.vehicleId != -1){
                this.sendVehiclePacket(packet -> this.sendPacket(player, packet));
            }
        });
        if (velocity != null)
            packets.add(this.buildVelocityPacket());
        return packets;
    }

    public void teleport(Location location) {
        this.sendPacket(this.buildTeleportPacket(location));
        this.location = location.clone();
        LibBL.get().entityService().moved(this);
    }

    public void teleport(Player player, Location location) {
        this.sendPacket(player, this.buildTeleportPacket(location));
    }

    public void forceTeleport(Location location, boolean onGround) {
        this.sendPacket(new WrapperPlayServerEntityTeleport(
                this.id,
                SpigotConversionUtil.fromBukkitLocation(location.clone()),
                onGround
        ));
        this.location = location.clone();
        LibBL.get().entityService().moved(this);
    }

    public void teleport(Player player, Location location, Location lastLocation) {
        this.sendPacket(player, this.buildTeleportPacket(location, lastLocation));
    }

    public void rotate(float yaw, float pitch) {
        this.location = this.rotated(yaw, pitch);

        this.sendPacket(this.buildRotationPacket());
    }

    public void rotate(Player player, float yaw, float pitch) {
        this.location = this.rotated(yaw, pitch);

        this.sendPacket(player, this.buildRotationPacket());
    }

    /**
     * A copy of the location facing the given way, see {@link #location}.
     */
    private Location rotated(float yaw, float pitch) {
        Location rotated = this.location.clone();
        rotated.setYaw(yaw);
        rotated.setPitch(pitch);
        return rotated;
    }

    public void despawn() {
        if (!this.alive)
            return;

        new PacketEntityDespawnEvent(this).callEvent();

        List<Player> viewers = this.viewersAsPlayer();
        LinkedList<PacketWrapper<?>> packets = this.buildDestroyPackets();
        for (PacketWrapper<?> packet : packets) {
            for (Player viewer : viewers) {
                this.sendPacket(viewer, packet);
            }
        }

        this.alive = false;
    }

    /**
     * Lets the visibility task hide the entity from players looking away from it, or not: an entity
     * a player works on should stay drawn behind them.
     */
    public void fieldOfViewCulled(boolean culled) {
        this.fieldOfViewCulled = culled;
    }

    /**
     * Gets rid of the entity for good: destroyed for its viewers, its viewers forgotten, and out of
     * the {@link io.rivrs.libbl.service.EntityService}. {@link #despawn()} alone keeps it registered
     * and remembers its viewers, for a later {@link #spawn()}.
     */
    public void remove() {
        this.despawn();
        this.viewers.clear();
        this.unregister();
    }

    protected LinkedList<PacketWrapper<?>> buildDestroyPackets() {
        LinkedList<PacketWrapper<?>> packets = new LinkedList<>();

        packets.add(this.buildDestroyPacket());

        return packets;
    }

    /**
     * Builds the vehicle packet reading the passengers of the real vehicle, which on Folia only the
     * region owning the vehicle may do. Prefer {@link #sendVehiclePacket(Consumer)}, which hops there.
     */
    protected WrapperPlayServerSetPassengers buildSetVehiclePacket() {
        return this.buildSetVehiclePacket(this.vehicle());
    }

    /**
     * The real entity this one rides, looked up by id: safe from any thread, unlike reading it.
     */
    protected @Nullable Entity vehicle() {
        Location location = this.location;
        if (this.vehicleId == -1 || location == null || location.getWorld() == null)
            return null;
        return SpigotConversionUtil.getEntityById(location.getWorld(), this.vehicleId);
    }

    /**
     * Builds the vehicle packet, then hands it to {@code sender} from the thread owning the vehicle:
     * its passengers are read there. With no real vehicle the packet only lists this entity, and is
     * handed over straight away. Nothing is sent when the vehicle is removed in the meantime.
     */
    protected void sendVehiclePacket(Consumer<PacketWrapper<?>> sender) {
        Entity vehicle = this.vehicle();
        if (vehicle == null || Bukkit.isOwnedByCurrentRegion(vehicle)) {
            sender.accept(this.buildSetVehiclePacket(vehicle));
            return;
        }

        vehicle.getScheduler().run(LibBL.get(), _ -> sender.accept(this.buildSetVehiclePacket(vehicle)), null);
    }

    /**
     * @param vehicle the real vehicle, whose passengers are read: call it from the thread owning it
     */
    protected WrapperPlayServerSetPassengers buildSetVehiclePacket(@Nullable Entity vehicle) {
        int[] passengers = new int[]{this.id};
        if(vehicle != null){
            List<Entity> passengerEntities = vehicle.getPassengers();
            int[] passengerIds = passengerEntities.stream().mapToInt(Entity::getEntityId).toArray();
            passengers = new int[passengerIds.length + 1];
            System.arraycopy(passengerIds, 0, passengers, 0, passengerIds.length);
            passengers[passengers.length - 1] = id;
        }
        return new WrapperPlayServerSetPassengers(
                this.vehicleId,
                passengers
        );
    }

    public void setVehicleId(Player player, int id) {
        this.vehicleId = id;
        this.sendVehiclePacket(packet -> this.sendPacket(player, packet));
    }

    public void setVehicleId(int id) {
        this.vehicleId = id;
        this.sendVehiclePacket(this::sendPacket);
    }

    protected WrapperPlayServerUpdateAttributes buildAttributePacket() {
        return new WrapperPlayServerUpdateAttributes(
                this.id,
                this.properties
        );
    }

    public void updateAttributes(Player player, List<WrapperPlayServerUpdateAttributes.Property> properties) {
        this.properties.addAll(properties);
        this.sendPacket(player, buildAttributePacket());
    }

    public void updateAttributes(List<WrapperPlayServerUpdateAttributes.Property> properties) {
        this.properties.addAll(properties);
        this.sendPacket(buildAttributePacket());
    }

    public void customName(Component customName) {
        this.customName = customName;
        this.updateMetadata();
    }

    public void customNameVisible(boolean visible) {
        this.customNameVisible = visible;
        this.updateMetadata();
    }

    public void silent(boolean silent) {
        this.silent = silent;
        this.updateMetadata();
    }

    public void gravity(boolean gravity) {
        this.gravity = gravity;
        this.updateMetadata();
    }

    public void pose(Pose pose) {
        this.pose = pose;
        this.updateMetadata();
    }

    public void frozenTicks(int ticks) {
        this.frozenTicks = ticks;
        this.updateMetadata();
    }

    public void airTicks(int ticks) {
        this.airTicks = ticks;
        this.updateMetadata();
    }

    public void register() {
        LibBL.get().entityService().register(this);
    }

    public void unregister() {
        LibBL.get().entityService().unregister(this);
    }

    // Particles
    public void particle(Vector vector, Particle particle) {
        sendPacket(ParticleUtils.create(location.clone().add(vector), particle));
    }

    public void particle(Vector vector, Location location, com.github.retrooper.packetevents.protocol.particle.Particle<?> particle) {
        sendPacket(ParticleUtils.create(location.clone().add(vector), particle));
    }

    // Flags
    public void addFlag(Flag flag) {
        this.flags.add(flag);

        this.updateMetadata();
    }

    public void addFlags(Flag... flags) {
        this.flags.addAll(Arrays.asList(flags));

        this.updateMetadata();
    }

    public void removeFlag(Flag flag) {
        this.flags.remove(flag);

        this.updateMetadata();
    }

    public boolean hasFlag(Flag flag) {
        return this.flags.contains(flag);
    }

    @Unmodifiable
    public Set<Flag> flags() {
        return Set.copyOf(this.flags);
    }

    @SuppressWarnings("unchecked")
    @Unmodifiable
    protected <T extends Flag> T[] flags(Class<T> type) {
        return this.flags.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toArray(size -> (T[]) Array.newInstance(type, size));
    }

    // Equipment
    public ItemStack equiment(EquipmentType type) {
        ItemStack itemStack = this.equipment[type.ordinal()];
        return itemStack != null ? itemStack : ItemStack.empty();
    }

    public boolean hasEquipment() {
        for (ItemStack item : this.equipment) {
            if (item != null)
                return true;
        }

        return false;
    }

    public boolean hasPassengers() {
        return !this.passengersIds.isEmpty();
    }

    public boolean hasEquipment(EquipmentType type) {
        return this.equipment[type.ordinal()] != null;
    }

    /**
     * Puts the item in the slot and shows it to the viewers. A null or empty item clears the slot:
     * it is sent as air, so viewers stop drawing what was there. Only that slot is sent: a packet
     * listing the remaining items would leave the cleared one on the client, and one listing none
     * cannot be decoded at all (the client disconnects).
     */
    public void equip(EquipmentType type, @Nullable ItemStack item) {
        boolean empty = item == null || item.isEmpty();
        this.equipment[type.ordinal()] = empty ? null : item;

        com.github.retrooper.packetevents.protocol.item.ItemStack sent = empty
                ? com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                : SpigotConversionUtil.fromBukkitItemStack(item);
        sendPacket(new WrapperPlayServerEntityEquipment(this.id, List.of(new Equipment(type.slot(), sent))));
    }

    public void addPassenger(PacketEntity entity) {
        if (this.passengersIds.add(entity))
            sendPacket(buildSetPassengersPacket());
    }

    public void removePassenger(PacketEntity entity) {
        if (this.passengersIds.remove(entity))
            sendPacket(buildSetPassengersPacket());
    }

    public void addVelocity(Vector3d velocity) {
        this.velocity = velocity;
        if (alive) {
            sendPacket(buildVelocityPacket());
        }
    }

    public void rotateHead(Player player, float yaw) {
        this.location = this.rotated(yaw, this.location.getPitch());
        this.sendPacket(player, new WrapperPlayServerEntityHeadLook(
                this.id,
                yaw
        ));
    }

    public void rotateHead(float yaw) {
        this.location = this.rotated(yaw, this.location.getPitch());
        this.sendPacket(new WrapperPlayServerEntityHeadLook(
                this.id,
                yaw
        ));
    }

    // Animations

    /**
     * Plays a client side animation on the entity, for every viewer. Purely visual, nothing about the
     * entity state changes.
     * <p>
     * The damage tilt has its own packet, see {@link #hurt(float)}.
     */
    public void animate(EntityAnimation animation) {
        this.sendPacket(this.buildAnimationPacket(animation));
    }

    public void animate(Player player, EntityAnimation animation) {
        this.sendPacket(player, this.buildAnimationPacket(animation));
    }

    /**
     * Swings the arm holding the given hand, following the same convention as
     * {@link io.rivrs.libbl.event.entity.PacketEntityInteractEvent}, where {@link MainHand#RIGHT} is the main hand.
     */
    public void swingHand(MainHand hand) {
        this.animate(this.swingAnimation(hand));
    }

    public void swingHand(Player player, MainHand hand) {
        this.animate(player, this.swingAnimation(hand));
    }

    /**
     * Plays the damage tilt animation, as if the hit came from straight ahead.
     */
    public void hurt() {
        this.hurt(0.0F);
    }

    /**
     * Plays the damage tilt animation.
     *
     * @param yaw direction the hit came from, in degrees relative to the entity body
     */
    public void hurt(float yaw) {
        this.sendPacket(this.buildHurtAnimationPacket(yaw));
    }

    public void hurt(Player player, float yaw) {
        this.sendPacket(player, this.buildHurtAnimationPacket(yaw));
    }

    /**
     * Plays the damage tilt animation, tilted towards the given position.
     */
    public void hurtFrom(Location source) {
        this.hurt(this.hurtDirection(source));
    }

    public void hurtFrom(Player player, Location source) {
        this.hurt(player, this.hurtDirection(source));
    }

    /**
     * Direction a hit coming from the given position makes the entity tilt towards, in degrees
     * relative to its body.
     */
    protected float hurtDirection(Location source) {
        return (float) (Math.atan2(source.getZ() - this.location.getZ(), source.getX() - this.location.getX()) * 180.0D / Math.PI
                        - this.location.getYaw());
    }

    private EntityAnimation swingAnimation(MainHand hand) {
        return hand == MainHand.LEFT ? EntityAnimation.SWING_OFF_HAND : EntityAnimation.SWING_MAIN_HAND;
    }

    // Metadata
    @Override
    public @NotNull List<EntityData<?>> entityData(@NotNull ClientVersion clientVersion) {
        List<EntityData<?>> entityData = new ArrayList<>();

        entityData.add(new EntityData<>(0, EntityDataTypes.BYTE, Flag.toBitMask(this.flags(EntityFlags.class)))); // Entity flags
        entityData.add(new EntityData<>(1, EntityDataTypes.INT, this.airTicks)); // Air ticks

        // Custom name
        if (customName != null && !customName.equals(Component.empty()))
            entityData.add(new EntityData<>(2, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.of(customName))); // Custom name
        entityData.add(new EntityData<>(3, EntityDataTypes.BOOLEAN, customNameVisible)); // Custom name visible

        entityData.add(new EntityData<>(4, EntityDataTypes.BOOLEAN, silent)); // Silent
        entityData.add(new EntityData<>(5, EntityDataTypes.BOOLEAN, !gravity)); // No gravity
        entityData.add(new EntityData<>(6, EntityDataTypes.ENTITY_POSE, SpigotConversionUtil.fromBukkitPose(this.pose))); // Pose
        entityData.add(new EntityData<>(7, EntityDataTypes.INT, this.frozenTicks)); // Frozen ticks

        return entityData;
    }

    // Viewers
    @Override
    public void addViewer(Player player) {
        // A player whose connection is not registered yet (still joining) is picked up by the next visibility pass
        if (LibBL.get().viewerService().getPlayerChannel(player.getUniqueId()) == null)
            return;

        if (this.isViewer(player)
                || !new PacketEntityAddViewerEvent(this, player, PacketEntityAddViewerEvent.Reason.PLUGIN).callEvent())
            return;

        // Another thread may have added the same viewer meanwhile, only one of them spawns the entity
        if (!this.viewers.add(player.getUniqueId()))
            return;

        LinkedList<PacketWrapper<?>> packets = this.buildSpawnPackets(player);
        for (PacketWrapper<?> packet : packets) {
            this.sendPacket(player, packet);
        }
    }

    @Override
    public void removeViewer(Player viewer) {
        if (!this.isViewer(viewer)
                || !new PacketEntityRemoveViewerEvent(this, viewer, PacketEntityRemoveViewerEvent.Reason.PLUGIN).callEvent())
            return;

        if (!this.viewers.remove(viewer.getUniqueId()))
            return;

        LinkedList<PacketWrapper<?>> packets = this.buildDestroyPackets();
        for (PacketWrapper<?> packet : packets) {
            this.sendPacket(viewer, packet);
        }
    }

    @Override
    public void removeViewer(UUID uniqueId) {
        Player viewer = Bukkit.getPlayer(uniqueId);
        if (viewer != null) {
            this.removeViewer(viewer);
            return;
        }

        // The player is offline, there is nothing left to send
        this.viewers.remove(uniqueId);
    }

    @Override
    public boolean isViewer(Player player) {
        return this.viewers.contains(player.getUniqueId());
    }

    @Override
    public boolean isViewer(UUID uniqueId) {
        return this.viewers.contains(uniqueId);
    }

    @Override
    public @Unmodifiable Set<UUID> viewers() {
        return Set.copyOf(this.viewers);
    }

    @Override
    public void autoViewable(boolean autoViewable) {
        this.autoViewable = autoViewable;
    }

    @Override
    public boolean isAutoViewable() {
        return this.autoViewable;
    }

    // Packets

    protected WrapperPlayServerSpawnEntity buildSpawnPacket() {
        return new WrapperPlayServerSpawnEntity(
                this.id,
                this.uniqueId,
                SpigotConversionUtil.fromBukkitEntityType(this.type),
                SpigotConversionUtil.fromBukkitLocation(this.location),
                this.location.getYaw(),
                0,
                null
        );
    }

    protected WrapperPlayServerDestroyEntities buildDestroyPacket() {
        return new WrapperPlayServerDestroyEntities(this.id);
    }

    protected PacketWrapper<?> buildTeleportPacket(Location newLocation) {
        return this.buildTeleportPacket(newLocation, this.location);
    }

    protected PacketWrapper<?> buildTeleportPacket(Location newLocation, Location lastLocation) {
        if (newLocation.x() == lastLocation.x()
                && newLocation.y() == lastLocation.y()
                && newLocation.z() == lastLocation.z()
                && (newLocation.getYaw() != lastLocation.getYaw()
                || newLocation.getPitch() != lastLocation.getPitch())) {
            return buildRotationPacket();
        }

        // Relative move packet
        if (newLocation.getWorld().equals(lastLocation.getWorld())
                && newLocation.distance(lastLocation) < 8) {
            return new WrapperPlayServerEntityRelativeMoveAndRotation(
                    this.id,
                    newLocation.getX() - lastLocation.getX(),
                    newLocation.getY() - lastLocation.getY(),
                    newLocation.getZ() - lastLocation.getZ(),
                    newLocation.getYaw(),
                    newLocation.getPitch(),
                    true
            );
        }

        // Teleport packet
        return new WrapperPlayServerEntityTeleport(
                this.id,
                SpigotConversionUtil.fromBukkitLocation(newLocation),
                true
        );
    }

    protected WrapperPlayServerEntityRotation buildRotationPacket() {
        return new WrapperPlayServerEntityRotation(
                this.id,
                this.location.getYaw(),
                this.location.getPitch(),
                true
        );
    }

    protected WrapperPlayServerEntityAnimation buildAnimationPacket(EntityAnimation animation) {
        return new WrapperPlayServerEntityAnimation(this.id, animation.type());
    }

    protected WrapperPlayServerHurtAnimation buildHurtAnimationPacket(float yaw) {
        return new WrapperPlayServerHurtAnimation(this.id, yaw);
    }

    protected WrapperPlayServerEntityEquipment buildEquipmentPacket() {
        // Build equipment list
        List<Equipment> equipments = new ArrayList<>();
        for (EquipmentType type : EquipmentType.values()) {
            ItemStack equipment = this.equipment[type.ordinal()];
            if (equipment != null && !equipment.isEmpty())
                equipments.add(new Equipment(type.slot(), SpigotConversionUtil.fromBukkitItemStack(equipment)));
        }

        return new WrapperPlayServerEntityEquipment(this.id, equipments);
    }

    protected PacketWrapper<WrapperPlayServerEntityMetadata> buildMetadataPacket() {
        return this.buildMetadataPacket(this);
    }

    protected PacketWrapper<WrapperPlayServerEntityMetadata> buildMetadataPacket(EntityMetadataProvider provider) {
        return new WrapperPlayServerEntityMetadata(
                this.id,
                provider
        );
    }

    protected PacketWrapper<WrapperPlayServerSetPassengers> buildSetPassengersPacket() {
        return new WrapperPlayServerSetPassengers(
                this.id,
                this.passengersIds.stream().mapToInt(i -> i.id).toArray()
        );
    }

    protected PacketWrapper<WrapperPlayServerEntityVelocity> buildVelocityPacket() {
        if (this.velocity == null)
            return null;
        Vector3d buffer = this.velocity;
        this.velocity = null;
        return new WrapperPlayServerEntityVelocity(
                this.id,
                buffer
        );
    }


    /**
     * Sends the metadata to every viewer, or, inside {@link #batch(Runnable)}, once the batch ends.
     */
    public void updateMetadata() {
        if (this.batchDepth > 0) {
            this.metadataPending = true;
            return;
        }
        this.sendPacket(this.buildMetadataPacket());
    }

    /**
     * Runs {@code changes} holding back the metadata every setter sends, then sends it once if any
     * of them changed it: setting ten values costs one packet instead of ten. Batches nest, the
     * outermost one sends. Meant for one thread at a time, like the setters themselves.
     */
    public void batch(Runnable changes) {
        this.batchDepth++;
        try {
            changes.run();
        } finally {
            if (--this.batchDepth == 0 && this.metadataPending) {
                this.metadataPending = false;
                this.updateMetadata();
            }
        }
    }

    public void updateMetadata(Player player) {
        this.sendPacket(player, this.buildMetadataPacket());
    }

    public void updateMetadata(Player player, EntityMetadataProvider provider) {
        this.sendPacket(player, this.buildMetadataPacket(provider));
    }

    public void updateMetadata(EntityMetadataProvider provider) {
        this.sendPacket(this.buildMetadataPacket(provider));
    }

    public void sendPacket(PacketWrapper<?>... packetWrappers) {
        if (!alive)
            return;

        ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();

        for (PacketWrapper<?> packetWrapper : packetWrappers) {
            for (Object channel : this.viewersAsChannel()) {
                protocolManager.sendPacket(channel, packetWrapper);
            }
        }
    }

    public void sendPacket(Object channel, PacketWrapper<?>... packetWrappers) {
        if (!alive)
            return;

        if (channel instanceof Player player) {
            channel = LibBL.get().viewerService().getPlayerChannel(player.getUniqueId());
        }

        ProtocolManager protocolManager = PacketEvents.getAPI().getProtocolManager();

        for (PacketWrapper<?> packetWrapper : packetWrappers) {
            protocolManager.sendPacket(channel, packetWrapper);
        }
    }

    public void addScheduledAction(long time, Consumer<PacketEntity> consumer) {
        this.consumers.computeIfAbsent(time, k -> ConcurrentHashMap.newKeySet()).add(consumer);
    }

    public void processScheduledActions(long now){
        List<Long> processKeys = consumers.keySet().stream().filter(time -> time <= now).toList();
        if (processKeys.isEmpty())
            return;
        for (Long key : processKeys) {
            Set<Consumer<PacketEntity>> toConsume = consumers.remove(key);
            toConsume.forEach(consumer -> consumer.accept(this));
        }
    }

    @Override
    public List<Object> viewersAsChannel() {
        return LibBL.get().viewerService().getPlayerChannels(this.viewers);
    }

    @Override
    public List<Player> viewersAsPlayer() {
        return viewers().stream()
                .map(Bukkit::getPlayer)
                .filter(Objects::nonNull)
                .toList();
    }
}
