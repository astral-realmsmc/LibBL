package io.rivrs.libbl.model.schematic;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import org.bukkit.Location;

/// An entity stored inside a schematic.
///
/// LibBL does not spawn those automatically : use the data to build the matching
/// [io.rivrs.libbl.model.entities.PacketEntity] yourself.
///
/// @param id    the namespaced identifier of the entity, e.g. `minecraft:armor_stand`
/// @param x     the x position of the entity, relative to the schematic
/// @param y     the y position of the entity, relative to the schematic
/// @param z     the z position of the entity, relative to the schematic
/// @param yaw   the yaw of the entity
/// @param pitch the pitch of the entity
/// @param data  the raw entity NBT, without its position and identifier
public record SchematicEntity(String id, double x, double y, double z, float yaw, float pitch, NBTCompound data) {

    /// Converts the relative position to a world location.
    ///
    /// @param corner the world location of the block at the relative position `0/0/0`
    public Location toLocation(Location corner) {
        return new Location(
                corner.getWorld(),
                corner.getBlockX() + this.x,
                corner.getBlockY() + this.y,
                corner.getBlockZ() + this.z,
                this.yaw,
                this.pitch
        );
    }
}
