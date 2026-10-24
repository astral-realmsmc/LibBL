package io.rivrs.libbl.model.schematic;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityType;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

/// The extra data carried by a block that owns a block entity (chests, signs, skulls, spawners, ...).
///
/// @param position the position of the block entity, relative to the schematic
/// @param id       the namespaced identifier of the block entity, e.g. `minecraft:sign`
/// @param data     the raw block entity NBT, without its position and identifier
public record SchematicBlockEntity(Vector3i position, String id, NBTCompound data) {

    /// Converts the relative position to a world location.
    ///
    /// @param corner the world location of the block at the relative position `0/0/0`
    public Location toLocation(Location corner) {
        return new Location(
                corner.getWorld(),
                corner.getBlockX() + this.position.getX(),
                corner.getBlockY() + this.position.getY(),
                corner.getBlockZ() + this.position.getZ()
        );
    }

    /// Builds the packet applying this block entity data to the client.
    ///
    /// Send it right after the block itself has been placed, otherwise the client discards it.
    ///
    /// @param corner the world location of the block at the relative position `0/0/0`
    /// @return the packet, or `null` if the current server version does not know this block entity type
    public @Nullable WrapperPlayServerBlockEntityData toPacket(Location corner) {
        BlockEntityType type = BlockEntityTypes.getByName(this.id);
        if (type == null)
            return null;

        return new WrapperPlayServerBlockEntityData(
                new Vector3i(
                        corner.getBlockX() + this.position.getX(),
                        corner.getBlockY() + this.position.getY(),
                        corner.getBlockZ() + this.position.getZ()
                ),
                type,
                this.data
        );
    }
}
