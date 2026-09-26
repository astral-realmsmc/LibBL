package io.rivrs.libbl.model.entities.entity.living;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.MainHand;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemProfile;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.HumanoidArm;

import io.rivrs.libbl.model.entities.entity.PacketLivingEntity;
import io.rivrs.libbl.model.flag.Flag;
import io.rivrs.libbl.model.flag.SkinPartFlags;
import lombok.Getter;
import net.kyori.adventure.text.Component;

/**
 * Player shaped statue, introduced in 1.21.9. Unlike a {@link PacketPlayer} it is a real entity type,
 * so it needs no player list entry and no team to hide its name, the skin comes from its own profile.
 * <p>
 * The profile is either <em>resolved</em>, when a {@link PacketPlayer.Skin} is set, or <em>unresolved</em>,
 * when only a {@link #profileName(String) name} or {@link #profileId(UUID) id} is set and the client
 * looks the skin up by itself.
 */
@Getter
public class PacketMannequin extends PacketLivingEntity {

    /**
     * Poses the client is able to render a mannequin in, any other one is rejected by {@link #pose(Pose)}.
     */
    public static final Set<Pose> VALID_POSES = Set.of(
            Pose.STANDING,
            Pose.SNEAKING,
            Pose.SWIMMING,
            Pose.FALL_FLYING,
            Pose.SLEEPING
    );

    /**
     * Default description of a mannequin, the label the client renders above it.
     */
    public static final Component DEFAULT_DESCRIPTION = Component.translatable("entity.minecraft.mannequin.label");

    private static final int MAX_PROFILE_NAME_LENGTH = 16;

    // Profile
    private @Nullable String profileName;
    private @Nullable UUID profileId;
    private PacketPlayer.@Nullable Skin skin;
    // Appearance
    private final Set<SkinPartFlags> skinParts = EnumSet.allOf(SkinPartFlags.class);
    private MainHand mainHand = MainHand.RIGHT;
    private @Nullable Component description = DEFAULT_DESCRIPTION;
    // Behaviour
    private boolean immovable;

    public PacketMannequin(Location location) {
        super(EntityType.MANNEQUIN, location);
    }

    public PacketMannequin(UUID uniqueId, Location location) {
        super(uniqueId, EntityType.MANNEQUIN, location);
    }

    public PacketMannequin(int id, UUID uniqueId, Location location) {
        super(id, uniqueId, EntityType.MANNEQUIN, location);
    }

    public PacketMannequin(Location location, @Nullable String profileName, PacketPlayer.@Nullable Skin skin) {
        this(location);

        this.checkProfileName(profileName);
        this.profileName = profileName;
        this.skin = skin;
    }

    // Profile

    /**
     * Replaces the whole profile at once, sending a single metadata update.
     *
     * @param name the profile name, at most 16 characters, {@code null} to leave it out
     * @param id   the profile id, {@code null} to leave it out
     * @param skin the textures, {@code null} to let the client resolve them from the name or the id
     */
    public void profile(@Nullable String name, @Nullable UUID id, PacketPlayer.@Nullable Skin skin) {
        this.checkProfileName(name);

        this.profileName = name;
        this.profileId = id;
        this.skin = skin;
        this.updateMetadata();
    }

    public void profileName(@Nullable String profileName) {
        this.checkProfileName(profileName);

        this.profileName = profileName;
        this.updateMetadata();
    }

    public void profileId(@Nullable UUID profileId) {
        this.profileId = profileId;
        this.updateMetadata();
    }

    public void skin(PacketPlayer.@Nullable Skin skin) {
        this.skin = skin;
        this.updateMetadata();
    }

    // Appearance

    /**
     * Shows the given skin parts and hides every other one.
     */
    public void skinParts(SkinPartFlags... parts) {
        this.skinParts.clear();
        this.skinParts.addAll(Arrays.asList(parts));
        this.updateMetadata();
    }

    public void showSkinPart(SkinPartFlags part) {
        if (this.skinParts.add(part))
            this.updateMetadata();
    }

    public void hideSkinPart(SkinPartFlags part) {
        if (this.skinParts.remove(part))
            this.updateMetadata();
    }

    public boolean hasSkinPart(SkinPartFlags part) {
        return this.skinParts.contains(part);
    }

    @Unmodifiable
    public Set<SkinPartFlags> skinParts() {
        return Set.copyOf(this.skinParts);
    }

    public void mainHand(MainHand mainHand) {
        this.mainHand = mainHand;
        this.updateMetadata();
    }

    /**
     * Label rendered above the mannequin, {@code null} to hide it.
     */
    public void description(@Nullable Component description) {
        this.description = description;
        this.updateMetadata();
    }

    // Behaviour

    /**
     * An immovable mannequin cannot be pushed nor knocked back, it is purely decorative.
     */
    public void immovable(boolean immovable) {
        this.immovable = immovable;
        this.updateMetadata();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when the pose is not one of {@link #VALID_POSES}
     */
    @Override
    public void pose(Pose pose) {
        if (!VALID_POSES.contains(pose))
            throw new IllegalArgumentException("A mannequin cannot be posed as " + pose + ", expected one of " + VALID_POSES);

        super.pose(pose);
    }

    @Override
    public List<EntityData<?>> entityData(@NotNull ClientVersion clientVersion) {
        List<EntityData<?>> entityData = super.entityData(clientVersion);
        entityData.add(new EntityData<>(15, EntityDataTypes.HUMANOID_ARM, this.mainHand == MainHand.LEFT ? HumanoidArm.LEFT : HumanoidArm.RIGHT)); // Main hand
        entityData.add(new EntityData<>(16, EntityDataTypes.BYTE, Flag.toBitMask(this.skinParts.toArray(SkinPartFlags[]::new)))); // Shown skin parts
        entityData.add(new EntityData<>(17, EntityDataTypes.RESOLVABLE_PROFILE, this.buildProfile())); // Profile
        entityData.add(new EntityData<>(18, EntityDataTypes.BOOLEAN, this.immovable)); // Immovable
        entityData.add(new EntityData<>(19, EntityDataTypes.OPTIONAL_ADV_COMPONENT, Optional.ofNullable(this.description))); // Description
        return entityData;
    }

    protected ItemProfile buildProfile() {
        List<ItemProfile.Property> properties = new ArrayList<>(1);
        if (this.skin != null)
            properties.add(new ItemProfile.Property("textures", this.skin.value(), this.skin.signature()));

        return new ItemProfile(this.profileName, this.profileId, properties);
    }

    private void checkProfileName(@Nullable String name) {
        if (name != null && name.length() > MAX_PROFILE_NAME_LENGTH)
            throw new IllegalArgumentException("A profile name cannot exceed " + MAX_PROFILE_NAME_LENGTH + " characters, got '" + name + "'.");
    }
}
