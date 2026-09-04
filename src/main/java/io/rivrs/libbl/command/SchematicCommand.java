package io.rivrs.libbl.command;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;

import io.papermc.paper.math.BlockPosition;
import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.model.block.BlockUpdateReport;
import io.rivrs.libbl.model.block.FakeBlock;
import io.rivrs.libbl.model.schematic.BlockSelection;
import io.rivrs.libbl.model.schematic.PasteAnchor;
import io.rivrs.libbl.model.schematic.Schematic;
import io.rivrs.libbl.utils.SchematicUtils;
import lombok.RequiredArgsConstructor;

/// Debug command placing a schematic as fake blocks, to eyeball the result in game.
///
/// ```
/// /fakeschem paste <x y z> <file> [corner|origin]
/// /fakeschem info <file>
/// /fakeschem clear
/// ```
///
/// A schematic name is looked up, with and without its `.schem` extension, in :
///  1. `plugins/LibBL/schematics/`, created on startup so that there is somewhere obvious to drop files
///  2. `plugins/LibBL/`, the plugin folder itself
///  3. `plugins/FastAsyncWorldEdit/schematics/` and `plugins/WorldEdit/schematics/`, where the
///     schematics saved in game already are
///  4. a plain path, absolute or relative to the server folder
///  5. the resources of the jar, which is how the bundled `fawe.schem` is found without copying it anywhere
///
/// Names may hold subfolders, e.g. `builds/castle.schem`, tab completion only lists the files sitting
/// directly inside the folders above.
@RequiredArgsConstructor
public class SchematicCommand {

    public static final String PERMISSION = "libbl.schematic";

    private static final String FOLDER = "schematics";
    private static final String EXTENSION = ".schem";

    private final LibBL plugin;

    /// Every block pasted by the command, so that `clear` can take them all back down.
    private final List<FakeBlock> pasted = new ArrayList<>();

    public LiteralCommandNode<CommandSourceStack> build() {
        // Give the schematics somewhere obvious to live
        File folder = new File(this.plugin.getDataFolder(), FOLDER);
        if (!folder.isDirectory() && !folder.mkdirs())
            this.plugin.getLogger().warning("Could not create the %s folder".formatted(folder.getPath()));

        return Commands.literal("fakeschem")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.literal("paste")
                        .then(Commands.argument("position", ArgumentTypes.blockPosition())
                                .then(Commands.argument("file", StringArgumentType.string())
                                        .suggests(this::suggestFiles)
                                        .executes(context -> this.paste(context, PasteAnchor.CORNER, true, BlockSelection.SOLID))
                                        .then(Commands.argument("anchor", StringArgumentType.word())
                                                .suggests(this::suggestAnchors)
                                                .executes(context -> this.pasteWithAnchor(context, true, BlockSelection.SOLID))
                                                .then(Commands.argument("events", StringArgumentType.word())
                                                        .suggests(this::suggestEvents)
                                                        .executes(context -> this.pasteWithEvents(context, BlockSelection.SOLID))
                                                        .then(Commands.argument("selection", StringArgumentType.word())
                                                                .suggests(this::suggestSelections)
                                                                .executes(this::pasteWithSelection)))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("file", StringArgumentType.string())
                                .suggests(this::suggestFiles)
                                .executes(this::info)))
                .then(Commands.literal("clear")
                        .executes(this::clear))
                .build();
    }

    private int pasteWithSelection(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String value = StringArgumentType.getString(context, "selection");
        BlockSelection selection;
        try {
            selection = BlockSelection.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            context.getSource().getSender().sendRichMessage("<red>Unknown selection '%s', expected all, solid or visible.".formatted(value));
            return 0;
        }

        return this.pasteWithEvents(context, selection);
    }

    private int pasteWithEvents(CommandContext<CommandSourceStack> context, BlockSelection selection) throws CommandSyntaxException {
        String value = StringArgumentType.getString(context, "events");
        if (!value.equalsIgnoreCase("events") && !value.equalsIgnoreCase("silent")) {
            context.getSource().getSender().sendRichMessage("<red>Expected events or silent, got '%s'.".formatted(value));
            return 0;
        }

        return this.pasteWithAnchor(context, value.equalsIgnoreCase("events"), selection);
    }

    private int pasteWithAnchor(CommandContext<CommandSourceStack> context, boolean fireEvents, BlockSelection selection) throws CommandSyntaxException {
        String value = StringArgumentType.getString(context, "anchor");
        PasteAnchor anchor;
        try {
            anchor = PasteAnchor.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            context.getSource().getSender().sendRichMessage("<red>Unknown anchor '%s', expected corner or origin.".formatted(value));
            return 0;
        }

        return this.paste(context, anchor, fireEvents, selection);
    }

    private int paste(CommandContext<CommandSourceStack> context, PasteAnchor anchor, boolean fireEvents, BlockSelection selection) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        CommandSender sender = source.getSender();

        World world = source.getLocation().getWorld();
        BlockPosition position = context.getArgument("position", BlockPositionResolver.class).resolve(source);
        Location location = new Location(world, position.blockX(), position.blockY(), position.blockZ());

        String name = StringArgumentType.getString(context, "file");
        sender.sendRichMessage("<gray>Reading <white>%s<gray>...".formatted(name));

        this.read(name).whenComplete((read, throwable) -> this.onMainThread(() -> {
            if (throwable != null) {
                sender.sendRichMessage("<red>Could not read %s : %s".formatted(name, message(throwable)));
                return;
            }

            Schematic schematic = read.schematic();

            int before = this.plugin.blockService().size();
            long collections = gcCount();
            long collected = gcMillis();

            long start = System.nanoTime();
            List<FakeBlock> blocks = schematic.buildFakeBlocks(location, anchor, selection);
            long allocated = System.nanoTime();
            long allocateGc = gcMillis() - collected;

            this.plugin.blockService().registerAll(blocks);
            long registered = System.nanoTime();
            long registerGc = gcMillis() - collected - allocateGc;

            // Blocks landing on an older paste replace it instead of adding to the registry
            int replaced = blocks.size() - (this.plugin.blockService().size() - before);

            BlockUpdateReport report = this.plugin.blockService().placeAll(blocks, fireEvents);
            this.pasted.addAll(blocks);

            int culled = selection == BlockSelection.VISIBLE
                    ? schematic.blockCount(BlockSelection.SOLID) - blocks.size()
                    : 0;

            this.report(sender, name, schematic, read, blocks.size(), replaced, culled,
                    allocated - start, allocateGc, registered - allocated, registerGc,
                    report, fireEvents, schematic.corner(location, anchor),
                    gcCount() - collections, gcMillis() - collected);
        }));

        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String name = StringArgumentType.getString(context, "file");

        this.read(name).whenComplete((read, throwable) -> this.onMainThread(() -> {
            if (throwable != null) {
                sender.sendRichMessage("<red>Could not read %s : %s".formatted(name, message(throwable)));
                return;
            }

            Schematic schematic = read.schematic();
            sender.sendRichMessage("<gray>%s <dark_gray>- <white>sponge v%d<gray>, data version <white>%d"
                    .formatted(name, schematic.version(), schematic.dataVersion()));
            sender.sendRichMessage("<gray>Size <white>%d x %d x %d<gray>, offset <white>%d %d %d"
                    .formatted(schematic.width(), schematic.height(), schematic.length(),
                            schematic.offset().getX(), schematic.offset().getY(), schematic.offset().getZ()));
            sender.sendRichMessage("<gray>Palette <white>%d<gray> states, <white>%d<gray> block entities, <white>%d<gray> entities"
                    .formatted(schematic.palette().size(), schematic.blockEntities().size(), schematic.entities().size()));
        }));

        return Command.SINGLE_SUCCESS;
    }

    private int clear(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (this.pasted.isEmpty()) {
            sender.sendRichMessage("<gray>Nothing was pasted by this command.");
            return 0;
        }

        int size = this.pasted.size();
        this.plugin.blockService().removeAll(this.pasted);
        this.pasted.forEach(FakeBlock::unregister);
        this.pasted.clear();

        sender.sendRichMessage("<green>Removed <white>%d<green> blocks.".formatted(size));
        return Command.SINGLE_SUCCESS;
    }

    /// What every phase of a read cost, so that the command can show where the time went.
    ///
    /// @param source     the file it came from, or `null` when it was read out of the jar
    /// @param bytes      the size of the file on the disk, still compressed
    /// @param readNanos  time spent pulling the bytes off the disk
    /// @param nbtNanos   time spent decompressing them and parsing the NBT tree
    /// @param decodeNanos time spent turning that tree into a palette and an array of block indices
    private record Read(Schematic schematic, @Nullable File source, int bytes,
                        long readNanos, long nbtNanos, long decodeNanos) {

        long totalNanos() {
            return this.readNanos + this.nbtNanos + this.decodeNanos;
        }
    }

    /// Reads a schematic off the main thread, from any of the folders listed on this class, from a
    /// plain path, or from the resources of the jar as a last resort.
    ///
    /// Goes through the bytes rather than [SchematicUtils#readAsync] so that the disk, the NBT
    /// parsing and the block decoding can be timed apart.
    private CompletableFuture<Read> read(String name) {
        File file = this.resolve(name);

        // Nothing on the disk, it may still be shipped inside the jar - fawe.schem is
        if (file == null && !this.hasResource(name)) {
            String folders = this.folders().stream()
                    .map(File::getPath)
                    .collect(Collectors.joining(", "));
            return CompletableFuture.failedFuture(new FileNotFoundException("%s was not found in %s".formatted(name, folders)));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                long start = System.nanoTime();
                byte[] bytes = file != null ? Files.readAllBytes(file.toPath()) : this.resourceBytes(name);
                long read = System.nanoTime();

                NBTCompound root = SchematicUtils.readNBT(new ByteArrayInputStream(bytes));
                long parsed = System.nanoTime();

                Schematic schematic = SchematicUtils.fromNBT(root);
                long decoded = System.nanoTime();

                return new Read(schematic, file, bytes.length, read - start, parsed - read, decoded - parsed);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private byte[] resourceBytes(String name) throws IOException {
        try (InputStream resource = this.plugin.getResource(name)) {
            if (resource == null)
                throw new FileNotFoundException("No resource named '%s' in %s".formatted(name, this.plugin.getName()));

            return resource.readAllBytes();
        }
    }

    private List<File> folders() {
        return folders(this.plugin.getDataFolder());
    }

    /// The folders a schematic name is looked up in, in order.
    static List<File> folders(File dataFolder) {
        File pluginsFolder = dataFolder.getParentFile();

        List<File> folders = new ArrayList<>(4);
        folders.add(new File(dataFolder, FOLDER));
        folders.add(dataFolder);
        if (pluginsFolder != null) {
            // Wherever the schematics saved in game already are
            folders.add(new File(pluginsFolder, "FastAsyncWorldEdit" + File.separator + FOLDER));
            folders.add(new File(pluginsFolder, "WorldEdit" + File.separator + FOLDER));
        }
        return folders;
    }

    private @Nullable File resolve(String name) {
        return resolve(this.plugin.getDataFolder(), name);
    }

    /// The file a schematic name points at, with or without its extension, or `null` when there is none.
    static @Nullable File resolve(File dataFolder, String name) {
        for (File folder : folders(dataFolder)) {
            for (File candidate : List.of(new File(folder, name), new File(folder, name + EXTENSION)))
                if (candidate.isFile())
                    return candidate;
        }

        // Absolute, or relative to the folder the server runs in
        for (File candidate : List.of(new File(name), new File(name + EXTENSION)))
            if (candidate.isFile())
                return candidate;

        return null;
    }

    private boolean hasResource(String name) {
        try (InputStream resource = this.plugin.getResource(name)) {
            return resource != null;
        } catch (IOException e) {
            return false;
        }
    }

    private CompletableFuture<Suggestions> suggestFiles(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        // Brigadier hands the opening quote of a quoted argument over, it is not part of the name
        String remaining = builder.getRemaining();
        if (remaining.startsWith("\""))
            remaining = remaining.substring(1);
        String prefix = remaining.toLowerCase(Locale.ROOT);

        Set<String> names = new LinkedHashSet<>();
        for (File folder : this.folders()) {
            File[] files = folder.listFiles();
            if (files == null)
                continue;

            for (File file : files)
                if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(EXTENSION))
                    names.add(file.getName());
        }

        // The one shipped inside the jar, so that the command can be tried out right away
        names.add("fawe.schem");

        for (String name : names)
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix))
                builder.suggest(name.contains(" ") ? '"' + name + '"' : name);

        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestSelections(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        for (BlockSelection selection : BlockSelection.values()) {
            String name = selection.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(prefix))
                builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestEvents(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        for (String value : List.of("events", "silent"))
            if (value.startsWith(prefix))
                builder.suggest(value);

        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestAnchors(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        for (PasteAnchor anchor : PasteAnchor.values()) {
            String name = anchor.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(prefix))
                builder.suggest(name);
        }
        return builder.buildFuture();
    }

    /// Fake blocks fire events and touch the block service, both of which belong to the main thread.
    private void onMainThread(Runnable action) {
        // The read may well outlive a reload, there is nothing to paste into anymore
        if (!this.plugin.isEnabled())
            return;

        if (this.plugin.getServer().isPrimaryThread()) {
            action.run();
            return;
        }
        this.plugin.getServer().getScheduler().runTask(this.plugin, action);
    }

    /// Prints where the time of a paste went, phase by phase, and what keeping it around costs.
    private void report(CommandSender sender, String name, Schematic schematic, Read read,
                        int blocks, int replaced, int culled, long allocateNanos, long allocateGc,
                        long registerNanos, long registerGc, BlockUpdateReport update,
                        boolean fireEvents, Location corner, long collections, long collected) {
        long total = read.totalNanos() + allocateNanos + registerNanos + update.totalNanos();

        sender.sendRichMessage("<green>Pasted <white>%s<green> - <white>%,d<green> blocks at <white>%d %d %d"
                .formatted(name, blocks, corner.getBlockX(), corner.getBlockY(), corner.getBlockZ()));

        phase(sender, "read from disk", read.readNanos(), total,
                "%s %s".formatted(bytes(read.bytes()), read.source() == null ? "from the jar" : "on disk"));
        phase(sender, "decompress + nbt", read.nbtNanos(), total, "");
        phase(sender, "decode blocks", read.decodeNanos(), total,
                "%,d blocks, palette of %,d".formatted(schematic.volume(), schematic.palette().size()));
        String kept = replaced > 0
                ? "%,d kept, %,d replaced an older paste".formatted(blocks, replaced)
                : culled > 0
                        ? "%,d kept, %,d walled in and skipped (%d%%)".formatted(blocks, culled, 100 * culled / (blocks + culled))
                        : "%,d kept, air skipped".formatted(blocks);
        phase(sender, "allocate blocks", allocateNanos, total, gc(allocateGc) + kept);
        phase(sender, "register blocks", registerNanos, total, gc(registerGc));
        phase(sender, fireEvents ? "events + viewers" : "viewers, no events", update.prepareNanos(), total,
                "%,d viewer%s".formatted(update.viewers(), update.viewers() == 1 ? "" : "s"));
        phase(sender, "build packets", update.buildNanos(), total,
                "%,d packet%s".formatted(update.packets(), update.packets() == 1 ? "" : "s"));
        phase(sender, "send", update.sendNanos(), total, "");
        sender.sendRichMessage("  <white>%-17s %9s".formatted("total", millis(total)));

        // Keeping the schematic around is the cheap way to paste again without touching the disk
        long schematicHeap = schematic.volume() * 4L + schematic.palette().size() * 64L;
        long blockHeap = (long) blocks * FakeBlock.APPROXIMATE_HEAP_SIZE;
        sender.sendRichMessage("  <gray>cached : schematic <white>~%s<gray>, fake blocks <white>~%s<gray> (%dx heavier)"
                .formatted(bytes(schematicHeap), bytes(blockHeap), blockHeap / Math.max(1L, schematicHeap)));
        if (replaced > 0)
            sender.sendRichMessage("  <yellow>%,d blocks landed on an older paste - clear it first for a clean timing.".formatted(replaced));

        // A paste allocates tens of megabytes in one burst, this says how much of the time the GC took
        if (collections > 0L)
            sender.sendRichMessage("  <gray>gc during the paste : <white>%,d<gray> collection%s, <white>%,d ms"
                    .formatted(collections, collections == 1L ? "" : "s", collected));
        sender.sendRichMessage("  <gray>Use <white>/fakeschem clear<gray> to take it back down.");
    }

    private static long gcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0L)
                total += count;
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long millis = bean.getCollectionTime();
            if (millis > 0L)
                total += millis;
        }
        return total;
    }

    private static void phase(CommandSender sender, String label, long nanos, long total, String detail) {
        sender.sendRichMessage("  <gray>%-17s <white>%9s <dark_gray>%3d%%<gray>  %s"
                .formatted(label, millis(nanos), total == 0L ? 0L : nanos * 100L / total, detail));
    }

    /// A stop the world pause lands inside whatever phase happens to be running, so say which one paid.
    private static String gc(long collected) {
        return collected > 0L ? "<red>%,d ms of gc<gray> ".formatted(collected) : "";
    }

    private static String millis(long nanos) {
        return "%.1f ms".formatted(nanos / 1_000_000.0D);
    }

    private static String bytes(long value) {
        if (value < 1024L)
            return value + " B";
        if (value < 1024L * 1024L)
            return "%.1f KB".formatted(value / 1024.0D);

        return "%.1f MB".formatted(value / (1024.0D * 1024.0D));
    }

    private static String message(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        // An unchecked IO exception only wraps the interesting one
        if (cause.getCause() instanceof FileNotFoundException notFound)
            cause = notFound;

        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
