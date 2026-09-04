package io.rivrs.libbl.model.schematic;

import com.github.retrooper.packetevents.util.Vector3i;
import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.model.block.FakeBlock;
import io.rivrs.libbl.utils.BlockPos;
import lombok.AccessLevel;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

/// An in-memory, read-only representation of a Sponge schematic (`.schem`), as written by
/// WorldEdit and FastAsyncWorldEdit.
///
/// Use [io.rivrs.libbl.utils.SchematicUtils] to load one, then [#paste(Location)] to recreate it
/// with packets :
/// ```java
/// Schematic schematic = SchematicUtils.read(this, "fawe.schem");
/// List<FakeBlock> blocks = schematic.paste(player.getLocation());
/// ```
///
/// Every position exposed by this class is relative to the schematic itself : `x` goes from `0` to
/// `width - 1`, `y` from `0` to `height - 1` and `z` from `0` to `length - 1`.
@Getter
public class Schematic {

    private static final Set<String> AIR_STATES = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "air", "cave_air", "void_air");

    private final int version;
    private final int dataVersion;
    private final int width;
    private final int height;
    private final int length;
    /// The position of the block `0/0/0` relatively to the point the schematic was copied from.
    private final Vector3i offset;
    /// The world position the schematic was copied from, or `null` if the file does not store it.
    private final @Nullable Vector3i origin;
    /// The block states used by this schematic, indexed the same way [#paletteIndex(int, int, int)] is.
    private final @Unmodifiable List<String> palette;
    private final @Unmodifiable Map<Vector3i, SchematicBlockEntity> blockEntities;
    private final @Unmodifiable List<SchematicEntity> entities;

    @Getter(AccessLevel.NONE)
    private final int[] blockIndices;
    @Getter(AccessLevel.NONE)
    private final AtomicReferenceArray<BlockData> parsedPalette;
    /// Per palette entry : 0 not looked at yet, 1 air, 2 solid. Resolving air-ness goes through a
    /// registry lookup, which is far too slow to redo for every one of the blocks of a schematic.
    @Getter(AccessLevel.NONE)
    private final byte[] airPalette;
    /// Protocol id per palette entry, -1 until resolved. Looking one up goes through a map keyed by
    /// block data, which is not something to redo for every block of a paste.
    @Getter(AccessLevel.NONE)
    private final int[] statePalette;

    public Schematic(int version,
                     int dataVersion,
                     int width,
                     int height,
                     int length,
                     Vector3i offset,
                     @Nullable Vector3i origin,
                     List<String> palette,
                     int[] blockIndices,
                     Map<Vector3i, SchematicBlockEntity> blockEntities,
                     List<SchematicEntity> entities) {
        if (width <= 0 || height <= 0 || length <= 0)
            throw new IllegalArgumentException("Schematic dimensions must be positive, got %d/%d/%d".formatted(width, height, length));

        long volume = (long) width * height * length;
        if (blockIndices.length != volume)
            throw new IllegalArgumentException("Schematic holds %d blocks but %d were expected".formatted(blockIndices.length, volume));

        this.version = version;
        this.dataVersion = dataVersion;
        this.width = width;
        this.height = height;
        this.length = length;
        this.offset = offset;
        this.origin = origin;
        this.palette = List.copyOf(palette);
        this.blockIndices = blockIndices;
        this.blockEntities = Map.copyOf(blockEntities);
        this.entities = List.copyOf(entities);
        this.parsedPalette = new AtomicReferenceArray<>(this.palette.size());
        this.airPalette = new byte[this.palette.size()];
        this.statePalette = new int[this.palette.size()];
        Arrays.fill(this.statePalette, -1);
    }

    /// The amount of blocks stored in this schematic, air included.
    public int volume() {
        return this.width * this.height * this.length;
    }

    public boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0
                && x < this.width && y < this.height && z < this.length;
    }

    /// The palette index of the block at the given relative position.
    public int paletteIndex(int x, int y, int z) {
        if (!this.contains(x, y, z))
            throw new IndexOutOfBoundsException("Position %d/%d/%d is outside of the schematic (%d/%d/%d)".formatted(x, y, z, this.width, this.height, this.length));

        return this.blockIndices[(y * this.length + z) * this.width + x];
    }

    /// The raw block state at the given relative position, e.g. `minecraft:grass_block[snowy=false]`.
    public String state(int x, int y, int z) {
        return this.palette.get(this.paletteIndex(x, y, z));
    }

    /// The parsed block state at the given relative position. The returned instance is a copy, mutating it is safe.
    public BlockData blockData(int x, int y, int z) {
        return this.parsedPalette(this.paletteIndex(x, y, z)).clone();
    }

    /// The block entity data at the given relative position, or `null` if this block owns none.
    public @Nullable SchematicBlockEntity blockEntity(int x, int y, int z) {
        return this.blockEntities.isEmpty()
                ? null
                : this.blockEntities.get(new Vector3i(x, y, z));
    }

    /// The whole block at the given relative position, air included.
    public SchematicBlock blockAt(int x, int y, int z) {
        int index = this.paletteIndex(x, y, z);
        return new SchematicBlock(
                x, y, z,
                this.palette.get(index),
                this.parsedPalette(index).clone(),
                this.blockEntity(x, y, z)
        );
    }

    /// Walks through every block of the schematic, skipping air, without allocating the whole list.
    public void forEach(Consumer<SchematicBlock> action) {
        this.forEach(false, action);
    }

    /// Walks through every block of the schematic without allocating the whole list.
    ///
    /// @param includeAir whether air blocks should be handed to the action
    public void forEach(boolean includeAir, Consumer<SchematicBlock> action) {
        this.forEach(includeAir ? BlockSelection.ALL : BlockSelection.SOLID, action);
    }

    /// Walks through the selected blocks of the schematic without allocating the whole list.
    public void forEach(BlockSelection selection, Consumer<SchematicBlock> action) {
        boolean[] occluding = selection == BlockSelection.VISIBLE ? this.occludingPalette() : null;

        for (int y = 0; y < this.height; y++) {
            for (int z = 0; z < this.length; z++) {
                for (int x = 0; x < this.width; x++) {
                    int index = this.blockIndices[(y * this.length + z) * this.width + x];
                    if (selection != BlockSelection.ALL && this.isAir(index))
                        continue;
                    if (occluding != null && isHidden(this.blockIndices, occluding, this.width, this.height, this.length, x, y, z))
                        continue;

                    action.accept(new SchematicBlock(
                            x, y, z,
                            this.palette.get(index),
                            this.parsedPalette(index).clone(),
                            this.blockEntity(x, y, z)
                    ));
                }
            }
        }
    }

    /// Every block of the schematic, air excluded.
    public List<SchematicBlock> blocks() {
        return this.blocks(false);
    }

    /// Every block of the schematic.
    ///
    /// @param includeAir whether air blocks should be part of the returned list
    public List<SchematicBlock> blocks(boolean includeAir) {
        return this.blocks(includeAir ? BlockSelection.ALL : BlockSelection.SOLID);
    }

    /// The selected blocks of the schematic.
    public List<SchematicBlock> blocks(BlockSelection selection) {
        List<SchematicBlock> blocks = new ArrayList<>(this.blockCount(selection));
        this.forEach(selection, blocks::add);
        return blocks;
    }

    /// Resolves the world location the block `0/0/0` will be placed at.
    ///
    /// @param location the location the schematic is pasted at
    /// @param anchor   how the schematic is positioned relatively to that location
    public Location corner(Location location, PasteAnchor anchor) {
        Location corner = new Location(
                location.getWorld(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
        return anchor == PasteAnchor.ORIGIN
                ? corner.add(this.offset.getX(), this.offset.getY(), this.offset.getZ())
                : corner;
    }

    /// Creates the [FakeBlock]s recreating this schematic, air excluded, without placing them.
    ///
    /// The blocks are registered against the [io.rivrs.libbl.service.BlockService] as soon as they
    /// are created : call [FakeBlock#place()] on each of them, or use [#paste(Location)] instead.
    ///
    /// @param location the location the lowest north-west corner of the schematic is pasted at
    public List<FakeBlock> createFakeBlocks(Location location) {
        return this.createFakeBlocks(location, PasteAnchor.CORNER, false);
    }

    /// Creates the [FakeBlock]s recreating this schematic without placing them.
    ///
    /// @param location   the location the schematic is pasted at
    /// @param anchor     how the schematic is positioned relatively to that location
    /// @param includeAir whether air blocks should be placed too, clearing whatever is already there
    public List<FakeBlock> createFakeBlocks(Location location, PasteAnchor anchor, boolean includeAir) {
        return this.createFakeBlocks(location, anchor, includeAir ? BlockSelection.ALL : BlockSelection.SOLID);
    }

    /// Creates - but does not place - the [FakeBlock]s recreating this schematic.
    ///
    /// [BlockSelection#VISIBLE] leaves out everything walled in on all six sides, which for a solid
    /// build means only its shell is created : fewer objects, fewer packets, same thing on screen.
    public List<FakeBlock> createFakeBlocks(Location location, PasteAnchor anchor, BlockSelection selection) {
        List<FakeBlock> fakeBlocks = this.buildFakeBlocks(location, anchor, selection);
        LibBL.get().blockService().registerAll(fakeBlocks);
        return fakeBlocks;
    }

    /// Builds the blocks of this schematic without adding them to the
    /// [io.rivrs.libbl.service.BlockService] - hand them to
    /// [io.rivrs.libbl.service.BlockService#registerAll(java.util.Collection)] before placing them.
    ///
    /// Only useful to keep the two costs apart, [#createFakeBlocks(Location, PasteAnchor, boolean)]
    /// does both.
    ///
    /// The blocks share the [BlockData] instances of the palette rather than each holding a copy, so
    /// never mutate what [FakeBlock#blockData()] hands back here - it would change every other block
    /// of the same state. Take a `clone()` first if you need to. [#blocks(boolean)] copies for you.
    public List<FakeBlock> buildFakeBlocks(Location location, PasteAnchor anchor, boolean includeAir) {
        return this.buildFakeBlocks(location, anchor, includeAir ? BlockSelection.ALL : BlockSelection.SOLID);
    }

    /// Builds the selected blocks without adding them to the [io.rivrs.libbl.service.BlockService].
    public List<FakeBlock> buildFakeBlocks(Location location, PasteAnchor anchor, BlockSelection selection) {
        Location corner = this.corner(location, anchor);
        World world = corner.getWorld();
        String worldName = world.getName();
        int cornerX = corner.getBlockX();
        int cornerY = corner.getBlockY();
        int cornerZ = corner.getBlockZ();

        // The hot path of a paste : walked by hand rather than through forEach, so that a schematic
        // of a hundred thousand blocks does not allocate a SchematicBlock it would throw away at once
        boolean[] occluding = selection == BlockSelection.VISIBLE ? this.occludingPalette() : null;

        List<FakeBlock> fakeBlocks = new ArrayList<>(this.blockCount(selection));
        for (int y = 0; y < this.height; y++) {
            for (int z = 0; z < this.length; z++) {
                for (int x = 0; x < this.width; x++) {
                    int index = this.blockIndices[(y * this.length + z) * this.width + x];
                    if (selection != BlockSelection.ALL && this.isAir(index))
                        continue;
                    if (occluding != null && isHidden(this.blockIndices, occluding, this.width, this.height, this.length, x, y, z))
                        continue;

                    fakeBlocks.add(new FakeBlock(
                            this.parsedPalette(index),
                            this.paletteStateID(index),
                            world,
                            worldName,
                            BlockPos.pack(cornerX + x, cornerY + y, cornerZ + z)
                    ));
                }
            }
        }
        return fakeBlocks;
    }

    /// How many blocks [#blocks(BlockSelection)] or
    /// [#createFakeBlocks(Location, PasteAnchor, BlockSelection)] would hand over, without building a
    /// single one of them.
    public int blockCount(boolean includeAir) {
        return this.blockCount(includeAir ? BlockSelection.ALL : BlockSelection.SOLID);
    }

    /// How many blocks an iteration would hand over, without building a single one of them.
    public int blockCount(BlockSelection selection) {
        if (selection == BlockSelection.ALL)
            return this.volume();

        if (selection == BlockSelection.SOLID) {
            int count = 0;
            for (int index : this.blockIndices)
                if (!this.isAir(index))
                    count++;

            return count;
        }

        boolean[] occluding = this.occludingPalette();
        int count = 0;
        for (int y = 0; y < this.height; y++)
            for (int z = 0; z < this.length; z++)
                for (int x = 0; x < this.width; x++) {
                    int index = this.blockIndices[(y * this.length + z) * this.width + x];
                    if (!this.isAir(index) && !isHidden(this.blockIndices, occluding, this.width, this.height, this.length, x, y, z))
                        count++;
                }

        return count;
    }

    /// Whether the block at the given position is walled in on all six sides by full opaque blocks,
    /// and could therefore not be seen by anybody.
    ///
    /// A neighbour sitting outside the schematic never hides anything : nothing is known about what
    /// stands there in the world, so the edges of a schematic are always kept.
    ///
    /// @param occluding whether each palette entry is a full opaque block, see [#occludingPalette()]
    static boolean isHidden(int[] blockIndices, boolean[] occluding, int width, int height, int length,
                            int x, int y, int z) {
        return occludes(blockIndices, occluding, width, height, length, x - 1, y, z)
                && occludes(blockIndices, occluding, width, height, length, x + 1, y, z)
                && occludes(blockIndices, occluding, width, height, length, x, y - 1, z)
                && occludes(blockIndices, occluding, width, height, length, x, y + 1, z)
                && occludes(blockIndices, occluding, width, height, length, x, y, z - 1)
                && occludes(blockIndices, occluding, width, height, length, x, y, z + 1);
    }

    private static boolean occludes(int[] blockIndices, boolean[] occluding, int width, int height, int length,
                                    int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length)
            return false;

        return occluding[blockIndices[(y * length + z) * width + x]];
    }

    /// Whether each palette entry is a full opaque block, worked out once for the whole schematic.
    private boolean[] occludingPalette() {
        boolean[] occluding = new boolean[this.palette.size()];
        for (int index = 0; index < occluding.length; index++)
            occluding[index] = !this.isAir(index) && this.parsedPalette(index).isOccluding();

        return occluding;
    }

    /// The protocol id of a palette entry, resolved once per entry rather than once per block.
    private int paletteStateID(int index) {
        int stateID = this.statePalette[index];
        if (stateID >= 0)
            return stateID;

        Integer resolved = LibBL.get().blockService().getDataState(this.parsedPalette(index));
        stateID = resolved == null ? 0 : resolved;
        this.statePalette[index] = stateID;
        return stateID;
    }

    /// Whether a palette entry is air, answered once per entry rather than once per block.
    ///
    /// Read off the palette string rather than the parsed [BlockData] : `getMaterial().isAir()` walks
    /// the block registry twice, which is far too slow to repeat for every block of a schematic, and
    /// it would force air entries to be parsed at all when they are about to be thrown away.
    private boolean isAir(int index) {
        byte cached = this.airPalette[index];
        if (cached != 0)
            return cached == 1;

        boolean air = isAirState(this.palette.get(index));
        // Racing threads compute the same answer and a byte write cannot tear
        this.airPalette[index] = (byte) (air ? 1 : 2);
        return air;
    }

    /// Whether a raw block state is one of the three air blocks. None of them carries a property, so
    /// an exact match is enough.
    static boolean isAirState(String state) {
        return AIR_STATES.contains(state);
    }

    /// Recreates this schematic with packets, air excluded.
    ///
    /// @param location the location the lowest north-west corner of the schematic is pasted at
    /// @return the placed blocks, keep them around to remove the schematic later on
    public List<FakeBlock> paste(Location location) {
        return this.paste(location, PasteAnchor.CORNER, false);
    }

    /// Recreates this schematic with packets.
    ///
    /// The blocks sharing a chunk section travel together in a single multi block change packet, see
    /// [io.rivrs.libbl.service.BlockService#placeAll(java.util.Collection)].
    ///
    /// Block entity data - sign text, banner patterns, skull textures, ... - is not sent by this
    /// method : send [SchematicBlockEntity#toPacket(Location)] yourself for the blocks needing it.
    ///
    /// @param location   the location the schematic is pasted at
    /// @param anchor     how the schematic is positioned relatively to that location
    /// @param includeAir whether air blocks should be placed too, clearing whatever is already there
    /// @return the placed blocks, keep them around to remove the schematic later on
    public List<FakeBlock> paste(Location location, PasteAnchor anchor, boolean includeAir) {
        return this.paste(location, anchor, includeAir ? BlockSelection.ALL : BlockSelection.SOLID);
    }

    /// Recreates this schematic with packets, keeping only the selected blocks.
    ///
    /// @return the placed blocks, keep them around to remove the schematic later on
    public List<FakeBlock> paste(Location location, PasteAnchor anchor, BlockSelection selection) {
        List<FakeBlock> fakeBlocks = this.createFakeBlocks(location, anchor, selection);
        LibBL.get().blockService().placeAll(fakeBlocks);
        return fakeBlocks;
    }

    /// Parses - once - the palette entry at the given index.
    ///
    /// Unknown block states are replaced by air rather than failing the whole schematic, so that a
    /// file written by a newer server, or holding modded blocks, stays usable.
    private BlockData parsedPalette(int index) {
        BlockData blockData = this.parsedPalette.get(index);
        if (blockData != null)
            return blockData;

        String state = this.palette.get(index);
        try {
            blockData = Bukkit.createBlockData(state);
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("[LibBL] Unknown block state '%s' in schematic, replaced by air.".formatted(state));
            blockData = Material.AIR.createBlockData();
        }

        this.parsedPalette.set(index, blockData);
        return blockData;
    }
}
