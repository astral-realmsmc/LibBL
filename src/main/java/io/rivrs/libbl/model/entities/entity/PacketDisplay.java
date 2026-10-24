package io.rivrs.libbl.model.entities.entity;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3f;
import io.rivrs.libbl.model.entities.PacketEntity;
import lombok.Getter;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

@Getter
public abstract class PacketDisplay extends PacketEntity {

    private int interpolationDelay;
    private int positionRotationInterpolationDelay;
    private int interpolationDuration;
    private Vector3f translation = new Vector3f();
    private Vector3f scale = new Vector3f(1, 1, 1);
    private Quaternion4f rotationLeft = new Quaternion4f(0, 0, 0, 1);
    private Quaternion4f rotationRight = new Quaternion4f(0, 0, 0, 1);
    private Display.Billboard billboard = Display.Billboard.FIXED;
    private int brightness = -1;
    private float viewRange = 1F;
    private float shadowRadius = 0F;
    private float shadowStrength = 1F;
    private float width = 0F;
    private float height = 0F;
    private Color glowColor = null;

    public PacketDisplay(EntityType type, Location location) {
        super(type, location);
    }

    public PacketDisplay(UUID uniqueId, EntityType type, Location location) {
        super(uniqueId, type, location);
    }

    public PacketDisplay(int id, UUID uniqueId, EntityType type, Location location) {
        super(id, uniqueId, type, location);
    }

    public void interpolationDuration(int interpolationDuration) {
        this.interpolationDuration = interpolationDuration;
        this.updateMetadata();
    }

    public void interpolationDelay(int interpolationDelay) {
        this.interpolationDelay = interpolationDelay;
        this.updateMetadata();
    }

    public void positionRotationInterpolationDelay(int positionRotationInterpolationDelay) {
        this.positionRotationInterpolationDelay = positionRotationInterpolationDelay;
        this.updateMetadata();
    }

    public void translation(Vector3f translation) {
        this.translation = translation;
        this.updateMetadata();
    }

    public void scale(Vector3f scale) {
        this.scale = scale;
        this.updateMetadata();
    }

    public void rotationLeft(Quaternion4f rotationLeft) {
        this.rotationLeft = rotationLeft;
        this.updateMetadata();
    }

    public void rotationRight(Quaternion4f rotationRight) {
        this.rotationRight = rotationRight;
        this.updateMetadata();
    }

    public void transformation(Transformation transformation) {
        this.translation = new Vector3f(transformation.getTranslation().x(), transformation.getTranslation().y(), transformation.getTranslation().z());
        this.rotationLeft = new Quaternion4f(transformation.getLeftRotation().x(), transformation.getLeftRotation().y(), transformation.getLeftRotation().z(), transformation.getLeftRotation().w());
        this.rotationRight = new Quaternion4f(transformation.getRightRotation().x(), transformation.getRightRotation().y(), transformation.getRightRotation().z(), transformation.getRightRotation().w());
        this.scale = new Vector3f(transformation.getScale().x(), transformation.getScale().y(), transformation.getScale().z());

        this.updateMetadata();
    }

    public void billboard(Display.Billboard billboard) {
        this.billboard = billboard;
        this.updateMetadata();
    }

    public void brightness(int brightness) {
        this.brightness = brightness;
        this.updateMetadata();
    }

    public void viewRange(float viewRange) {
        this.viewRange = viewRange;
        this.updateMetadata();
    }

    public void shadowRadius(float shadowRadius) {
        this.shadowRadius = shadowRadius;
        this.updateMetadata();
    }

    public void shadowStrength(float shadowStrength) {
        this.shadowStrength = shadowStrength;
        this.updateMetadata();
    }

    public void width(float width) {
        this.width = width;
        this.updateMetadata();
    }

    public void height(float height) {
        this.height = height;
        this.updateMetadata();
    }

    public void glowColor(Color glowColor) {
        this.glowColor = glowColor;
        this.updateMetadata();
    }

    @Override
    public @NotNull List<EntityData<?>> entityData(@NotNull ClientVersion clientVersion) {
        List<EntityData<?>> entityData = super.entityData(clientVersion);
        entityData.add(new EntityData<>(8, EntityDataTypes.INT, this.interpolationDelay));
        entityData.add(new EntityData<>(9, EntityDataTypes.INT, this.interpolationDuration));
        entityData.add(new EntityData<>(10, EntityDataTypes.INT, this.positionRotationInterpolationDelay));
        entityData.add(new EntityData<>(11, EntityDataTypes.VECTOR3F, this.translation));
        entityData.add(new EntityData<>(12, EntityDataTypes.VECTOR3F, this.scale));
        entityData.add(new EntityData<>(13, EntityDataTypes.QUATERNION, this.rotationLeft));
        entityData.add(new EntityData<>(14, EntityDataTypes.QUATERNION, this.rotationRight));
        entityData.add(new EntityData<>(15, EntityDataTypes.BYTE, (byte) this.billboard.ordinal()));
        entityData.add(new EntityData<>(16, EntityDataTypes.INT, this.brightness));
        entityData.add(new EntityData<>(17, EntityDataTypes.FLOAT, this.viewRange));
        entityData.add(new EntityData<>(18, EntityDataTypes.FLOAT, this.shadowRadius));
        entityData.add(new EntityData<>(19, EntityDataTypes.FLOAT, this.shadowStrength));
        entityData.add(new EntityData<>(20, EntityDataTypes.FLOAT, this.width));
        entityData.add(new EntityData<>(21, EntityDataTypes.FLOAT, this.height));
        entityData.add(new EntityData<>(22, EntityDataTypes.INT, this.glowColor == null ? -1 : this.glowColor.asRGB()));
        return entityData;
    }
}
