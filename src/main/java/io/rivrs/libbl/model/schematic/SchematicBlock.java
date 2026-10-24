package io.rivrs.libbl.model.schematic;

import com.github.retrooper.packetevents.util.Vector3i;
import io.rivrs.libbl.model.block.FakeBlock;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

/// A single block read from a [Schematic], with its position relative to the schematic itself.
///
/// @param x           the x position of the block, between `0` and `width - 1`
/// @param y           the y position of the block, between `0` and `height - 1`
/// @param z           the z position of the block, between `0` and `length - 1`
/// @param state       the raw block state, e.g. `minecraft:grass_block[snowy=false]`
/// @param blockData   the parsed block state, safe to mutate
/// @param blockEntity the block entity data of this block, or `null` if it has none
public record SchematicBlock(int x, int y, int z, String state, BlockData blockData,
                             @Nullable SchematicBlockEntity blockEntity) {

    /// The position of the block, relative to the schematic.
    public Vector3i position() {
        return new Vector3i(this.x, this.y, this.z);
    }

    public boolean isAir() {
        return this.blockData.getMaterial().isAir();
    }

    public boolean hasBlockEntity() {
        return this.blockEntity != null;
    }

    /// Converts the relative position to a world location.
    ///
    /// @param corner the world location of the block at the relative position `0/0/0`
    public Location toLocation(Location corner) {
        return new Location(
                corner.getWorld(),
                corner.getBlockX() + this.x,
                corner.getBlockY() + this.y,
                corner.getBlockZ() + this.z
        );
    }

    /// Creates - but does not place - the [FakeBlock] recreating this block in the world.
    ///
    /// @param corner the world location of the block at the relative position `0/0/0`
    public FakeBlock toFakeBlock(Location corner) {
        return new FakeBlock(this.blockData, this.toLocation(corner));
    }
}
