package io.rivrs.libbl.utils;

import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTByteArray;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTIntArray;
import com.github.retrooper.packetevents.protocol.nbt.NBTLimiter;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTNumber;
import com.github.retrooper.packetevents.protocol.nbt.serializer.DefaultNBTSerializer;
import com.github.retrooper.packetevents.util.Vector3i;
import io.rivrs.libbl.model.schematic.Schematic;
import io.rivrs.libbl.model.schematic.SchematicBlockEntity;
import io.rivrs.libbl.model.schematic.SchematicEntity;
import lombok.experimental.UtilityClass;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/// Reads Sponge schematics (`.schem`), the format written by WorldEdit and FastAsyncWorldEdit, so
/// that their blocks can be recreated with packets.
///
/// Versions 1, 2 and 3 of the format are supported, gzipped or not. The legacy MCEdit format
/// (`.schematic`, numeric block ids) is not : convert it with WorldEdit first.
///
/// ```java
/// Schematic schematic = SchematicUtils.read(this, "fawe.schem");
/// List<FakeBlock> blocks = schematic.paste(player.getLocation());
/// ```
///
/// Reading does not touch the server state, so it is safe to do it off the main thread - see
/// [#readAsync(File)].
@UtilityClass
public class SchematicUtils {

    private static final String AIR = "minecraft:air";

    /// Reads a schematic from a file.
    ///
    /// @throws IOException              if the file cannot be read
    /// @throws IllegalArgumentException if the file is not a valid Sponge schematic
    public static Schematic read(File file) throws IOException {
        return read(file.toPath());
    }

    /// Reads a schematic from a file.
    ///
    /// @throws IOException              if the file cannot be read
    /// @throws IllegalArgumentException if the file is not a valid Sponge schematic
    public static Schematic read(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return read(input);
        }
    }

    /// Reads a schematic shipped inside the jar of a plugin, e.g. `fawe.schem` sitting in its resources.
    ///
    /// @throws FileNotFoundException    if the plugin holds no such resource
    /// @throws IOException              if the resource cannot be read
    /// @throws IllegalArgumentException if the resource is not a valid Sponge schematic
    public static Schematic read(Plugin plugin, String resource) throws IOException {
        try (InputStream input = plugin.getResource(resource)) {
            if (input == null)
                throw new FileNotFoundException("No resource named '%s' in %s".formatted(resource, plugin.getName()));

            return read(input);
        }
    }

    /// Reads a schematic from raw bytes.
    ///
    /// @throws IOException              if the bytes cannot be read
    /// @throws IllegalArgumentException if the bytes are not a valid Sponge schematic
    public static Schematic read(byte[] bytes) throws IOException {
        try (InputStream input = new ByteArrayInputStream(bytes)) {
            return read(input);
        }
    }

    /// Reads a schematic from a stream, gzipped or not. The stream is left open.
    ///
    /// @throws IOException              if the stream cannot be read
    /// @throws IllegalArgumentException if the stream does not hold a valid Sponge schematic
    public static Schematic read(InputStream input) throws IOException {
        return fromNBT(readNBT(input));
    }

    /// Reads a schematic off the main thread.
    ///
    /// The returned future fails with an [UncheckedIOException] if the file cannot be read.
    public static CompletableFuture<Schematic> readAsync(File file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return read(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /// Reads a schematic shipped inside the jar of a plugin, off the main thread.
    ///
    /// The returned future fails with an [UncheckedIOException] if the resource cannot be read.
    public static CompletableFuture<Schematic> readAsync(Plugin plugin, String resource) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return read(plugin, resource);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /// Reads the NBT root tag of a schematic, gzipped or not. The stream is left open.
    public static NBTCompound readNBT(InputStream input) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(new BufferedInputStream(input), 2);

        int first = pushback.read();
        if (first == -1)
            throw new IOException("Empty schematic stream");

        int second = pushback.read();
        if (second != -1)
            pushback.unread(second);
        pushback.unread(first);

        if (first != 0x1F || second != 0x8B)
            return deserialize(new DataInputStream(pushback));

        // Closing the gzip layer releases its inflater, the shield keeps the given stream open
        try (InputStream gzip = new GZIPInputStream(new UnclosableInputStream(pushback))) {
            return deserialize(new DataInputStream(gzip));
        }
    }

    private static NBTCompound deserialize(DataInputStream data) throws IOException {
        NBT root = DefaultNBTSerializer.INSTANCE.deserializeTag(NBTLimiter.noop(), data, true);
        if (!(root instanceof NBTCompound compound))
            throw new IOException("Schematic root tag is %s, expected a compound".formatted(root == null ? "empty" : root.getType()));

        return compound;
    }

    /// Builds a schematic out of an already read NBT root tag.
    ///
    /// @throws IllegalArgumentException if the tag does not hold a valid Sponge schematic
    public static Schematic fromNBT(NBTCompound root) {
        // Version 3 wraps everything inside a "Schematic" tag, older ones keep it at the root
        NBTCompound tag = root.getCompoundTagOrNull("Schematic");
        if (tag == null)
            tag = root;

        if (tag.getTagOfTypeOrNull("Blocks", NBTByteArray.class) != null)
            throw new IllegalArgumentException("Legacy MCEdit schematics are not supported, convert the file to the Sponge format first");

        int version = intOrDefault(tag, "Version", 1);
        int dataVersion = intOrDefault(tag, "DataVersion", -1);
        int width = unsignedShort(tag, "Width");
        int height = unsignedShort(tag, "Height");
        int length = unsignedShort(tag, "Length");

        Vector3i offset = vector(tag, "Offset");
        if (offset == null)
            offset = new Vector3i(0, 0, 0);

        // Version 3 blocks live in their own tag, older ones spread them over the root
        NBTCompound blocksTag = tag.getCompoundTagOrNull("Blocks");
        NBTCompound paletteTag = blocksTag != null ? blocksTag.getCompoundTagOrNull("Palette") : tag.getCompoundTagOrNull("Palette");
        NBTByteArray dataTag = blocksTag != null
                ? blocksTag.getTagOfTypeOrNull("Data", NBTByteArray.class)
                : tag.getTagOfTypeOrNull("BlockData", NBTByteArray.class);

        if (paletteTag == null || dataTag == null)
            throw new IllegalArgumentException("Schematic is missing its block palette or its block data");

        long volume = (long) width * height * length;
        if (volume > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Schematic holds %d blocks, too many to be loaded at once".formatted(volume));

        List<String> palette = readPalette(paletteTag);
        int[] blockIndices = readBlocks(dataTag.getValue(), (int) volume, palette.size());

        NBTList<NBTCompound> blockEntitiesTag = blocksTag != null
                ? blocksTag.getCompoundListTagOrNull("BlockEntities")
                : firstNonNull(tag.getCompoundListTagOrNull("BlockEntities"), tag.getCompoundListTagOrNull("TileEntities"));

        return new Schematic(
                version,
                dataVersion,
                width,
                height,
                length,
                offset,
                readOrigin(tag),
                palette,
                blockIndices,
                readBlockEntities(blockEntitiesTag),
                readEntities(tag.getCompoundListTagOrNull("Entities"))
        );
    }

    /// Turns the `name -> index` palette of the file into an `index -> name` list.
    private static List<String> readPalette(NBTCompound paletteTag) {
        Map<Integer, String> states = new HashMap<>();
        int max = -1;
        for (String state : paletteTag.getTagNames()) {
            Number index = paletteTag.getNumberTagValueOrNull(state);
            if (index == null)
                continue;

            states.put(index.intValue(), state);
            max = Math.max(max, index.intValue());
        }

        if (max < 0)
            throw new IllegalArgumentException("Schematic block palette is empty");

        List<String> palette = new ArrayList<>(max + 1);
        for (int index = 0; index <= max; index++)
            palette.add(states.getOrDefault(index, AIR));

        return palette;
    }

    /// Decodes the varint stream holding one palette index per block, in `y -> z -> x` order.
    private static int[] readBlocks(byte[] data, int volume, int paletteSize) {
        int[] blocks = new int[volume];
        int cursor = 0;
        int block = 0;

        while (cursor < data.length) {
            if (block >= volume)
                throw new IllegalArgumentException("Schematic holds more blocks than its %d dimensions allow".formatted(volume));

            int value = 0;
            int bits = 0;
            while (true) {
                if (cursor >= data.length)
                    throw new IllegalArgumentException("Schematic block data ends in the middle of a block");
                if (bits > 4 * 7)
                    throw new IllegalArgumentException("Schematic block data is corrupted, varint is too big");

                byte current = data[cursor++];
                value |= (current & 0x7F) << bits;
                bits += 7;

                if ((current & 0x80) == 0)
                    break;
            }

            if (value < 0 || value >= paletteSize)
                throw new IllegalArgumentException("Schematic block %d points at the palette entry %d, out of the %d known ones".formatted(block, value, paletteSize));

            blocks[block++] = value;
        }

        if (block != volume)
            throw new IllegalArgumentException("Schematic holds %d blocks but %d were expected".formatted(block, volume));

        return blocks;
    }

    private static Map<Vector3i, SchematicBlockEntity> readBlockEntities(@Nullable NBTList<NBTCompound> blockEntitiesTag) {
        if (blockEntitiesTag == null)
            return Map.of();

        Map<Vector3i, SchematicBlockEntity> blockEntities = new LinkedHashMap<>();
        for (NBTCompound tag : blockEntitiesTag.getTags()) {
            Vector3i position = vector(tag, "Pos");
            if (position == null)
                // Version 1 stores the position as three loose tags
                position = looseVector(tag);
            if (position == null)
                continue;

            String id = firstNonNull(tag.getStringTagValueOrNull("Id"), tag.getStringTagValueOrNull("id"));
            if (id == null)
                continue;

            blockEntities.put(position, new SchematicBlockEntity(position, id, extraData(tag)));
        }
        return blockEntities;
    }

    private static List<SchematicEntity> readEntities(@Nullable NBTList<NBTCompound> entitiesTag) {
        if (entitiesTag == null)
            return List.of();

        List<SchematicEntity> entities = new ArrayList<>();
        for (NBTCompound tag : entitiesTag.getTags()) {
            NBTList<NBTNumber> position = tag.getNumberListTagOrNull("Pos");
            if (position == null || position.size() < 3)
                continue;

            String id = firstNonNull(tag.getStringTagValueOrNull("Id"), tag.getStringTagValueOrNull("id"));
            if (id == null)
                continue;

            NBTList<NBTNumber> rotation = tag.getNumberListTagOrNull("Rotation");
            entities.add(new SchematicEntity(
                    id,
                    position.getTag(0).getAsDouble(),
                    position.getTag(1).getAsDouble(),
                    position.getTag(2).getAsDouble(),
                    rotation != null && rotation.size() > 0 ? rotation.getTag(0).getAsFloat() : 0f,
                    rotation != null && rotation.size() > 1 ? rotation.getTag(1).getAsFloat() : 0f,
                    extraData(tag)
            ));
        }
        return entities;
    }

    /// Extracts the payload of a block entity or of an entity : version 3 keeps it in its own tag,
    /// older ones inline it next to the position and the identifier.
    private static NBTCompound extraData(NBTCompound tag) {
        NBTCompound data = tag.getCompoundTagOrNull("Data");
        if (data != null)
            return data;

        data = tag.copy();
        for (String key : List.of("Pos", "Id", "Rotation", "id", "x", "y", "z"))
            data.removeTag(key);

        return data;
    }

    private static @Nullable Vector3i readOrigin(NBTCompound tag) {
        NBTCompound metadata = tag.getCompoundTagOrNull("Metadata");
        if (metadata == null)
            return null;

        NBTCompound worldEdit = metadata.getCompoundTagOrNull("WorldEdit");
        return worldEdit == null ? null : vector(worldEdit, "Origin");
    }

    private static @Nullable Vector3i vector(NBTCompound tag, String key) {
        NBTIntArray array = tag.getTagOfTypeOrNull(key, NBTIntArray.class);
        if (array == null || array.getValue().length < 3)
            return null;

        int[] values = array.getValue();
        return new Vector3i(values[0], values[1], values[2]);
    }

    private static @Nullable Vector3i looseVector(NBTCompound tag) {
        Number x = tag.getNumberTagValueOrNull("x");
        Number y = tag.getNumberTagValueOrNull("y");
        Number z = tag.getNumberTagValueOrNull("z");
        return x == null || y == null || z == null
                ? null
                : new Vector3i(x.intValue(), y.intValue(), z.intValue());
    }

    private static int intOrDefault(NBTCompound tag, String key, int defaultValue) {
        Number value = tag.getNumberTagValueOrNull(key);
        return value == null ? defaultValue : value.intValue();
    }

    /// Dimensions are stored as shorts, which caps them at 65535 rather than 32767.
    private static int unsignedShort(NBTCompound tag, String key) {
        Number value = tag.getNumberTagValueOrNull(key);
        if (value == null)
            throw new IllegalArgumentException("Schematic is missing its '%s' tag, is it really a Sponge schematic ?".formatted(key));

        // Written as a short, so anything above 32767 comes back negative
        int dimension = value.intValue();
        return dimension < 0 ? dimension & 0xFFFF : dimension;
    }

    private static <T> @Nullable T firstNonNull(@Nullable T first, @Nullable T second) {
        return first != null ? first : second;
    }

    /// Keeps the stream handed over by the caller open when the gzip layer sitting on top of it is closed.
    private static final class UnclosableInputStream extends FilterInputStream {

        private UnclosableInputStream(InputStream input) {
            super(input);
        }

        @Override
        public void close() {
            // The caller owns the stream, it closes it whenever it wants to
        }
    }
}
