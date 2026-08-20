package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import com.fastasyncworldedit.core.math.random.SimpleRandom;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.function.pattern.RandomPattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperEditPreparation implements AutoCloseable {
    private final JavaPlugin plugin;
    private final MainThread mainThread;
    private final ChunkTicketManager tickets;

    PaperEditPreparation(JavaPlugin plugin, MainThread mainThread) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.tickets = new ChunkTicketManager(mainThread);
    }

    void beginStopping() {
        this.tickets.beginStopping();
    }

    PaperWorld resolveWorld(String worldName) throws OperationException {
        return onMainThread(
                () -> {
                    World world = this.plugin.getServer().getWorld(worldName);
                    if (world == null) {
                        throw new OperationException(
                                OperationFailure.WORLD_NOT_FOUND,
                                "World is not loaded: " + worldName);
                    }
                    return new PaperWorld(
                            world.getUID(),
                            world.getName(),
                            world,
                            BukkitAdapter.adapt(world),
                            this.plugin);
                });
    }

    PreparedReplace prepareReplace(
            PaperWorld world, ReplaceRegionBlocks.Request request, Cuboid region)
            throws OperationException {
        return onMainThread(
                () -> {
                    requireAvailable(world);
                    requireValidHeight(world.bukkitWorld(), region);
                    PreparedSources sources = prepareSources(request.sourceBlockStatePatterns());
                    PreparedPalette palette =
                            preparePalette(request.destinationPalette(), request.seed());
                    List<ChunkPosition> chunks = chunks(region);
                    ChunkTicketManager.Lease lease = this.tickets.acquire(world, chunks, "Region");
                    return new PreparedReplace(world, sources, palette, chunks, lease);
                });
    }

    PreparedFill prepareFill(PaperWorld world, FillRegion.Request request, Cuboid region)
            throws OperationException {
        return onMainThread(
                () -> {
                    requireAvailable(world);
                    requireValidHeight(world.bukkitWorld(), region);
                    PreparedPalette palette =
                            preparePalette(request.destinationPalette(), request.seed());
                    List<ChunkPosition> chunks = chunks(region);
                    ChunkTicketManager.Lease lease = this.tickets.acquire(world, chunks, "Region");
                    return new PreparedFill(world, palette, chunks, lease);
                });
    }

    PreparedSet prepareSet(
            PaperWorld world, SetBlocks.Request request, List<ChunkPosition> touchedChunks)
            throws OperationException {
        return onMainThread(
                () -> {
                    requireAvailable(world);
                    List<PreparedBlockChange> changes = prepareChanges(world, request.changes());
                    ChunkTicketManager.Lease lease =
                            this.tickets.acquire(world, touchedChunks, "Sparse edit");
                    return new PreparedSet(world, changes, List.copyOf(touchedChunks), lease);
                });
    }

    ChunkTicketManager.Lease prepareUndo(PaperWorld world, List<ChunkPosition> chunks)
            throws OperationException {
        return onMainThread(
                () -> {
                    requireAvailable(world);
                    return this.tickets.acquire(world, chunks, "Undo operation");
                });
    }

    @Override
    public void close() {
        this.tickets.close();
    }

    private List<PreparedBlockChange> prepareChanges(
            PaperWorld world, List<BlockChange> requestedChanges) throws OperationException {
        Map<BlockPosition, Integer> positions = new HashMap<>();
        Map<String, BlockState> parsedStates = new HashMap<>();
        List<PreparedBlockChange> changes = new ArrayList<>(requestedChanges.size());
        for (int index = 0; index < requestedChanges.size(); index++) {
            BlockChange change = requestedChanges.get(index);
            BlockPosition position = change.position();
            if (position.y() < world.bukkitWorld().getMinHeight()
                    || position.y() >= world.bukkitWorld().getMaxHeight()) {
                throw invalid(
                        "changes["
                                + index
                                + "].position.y must be between "
                                + world.bukkitWorld().getMinHeight()
                                + " and "
                                + (world.bukkitWorld().getMaxHeight() - 1));
            }
            Integer previous = positions.putIfAbsent(position, index);
            if (previous != null) {
                throw invalid(
                        "changes["
                                + index
                                + "].position duplicates changes["
                                + previous
                                + "].position");
            }
            if (change.blockState() == null || change.blockState().isBlank()) {
                throw invalid("changes[" + index + "].blockState must be a non-empty string");
            }
            BlockState state = parsedStates.get(change.blockState());
            if (state == null) {
                state =
                        BukkitAdapter.adapt(
                                parseBlockData(
                                        change.blockState(), "changes[" + index + "].blockState"));
                parsedStates.put(change.blockState(), state);
            }
            changes.add(
                    new PreparedBlockChange(
                            BlockVector3.at(position.x(), position.y(), position.z()), state));
        }
        return List.copyOf(changes);
    }

    private static PreparedSources prepareSources(List<String> inputs) throws OperationException {
        if (inputs == null || inputs.isEmpty()) {
            throw invalid("sourceBlockStatePatterns must contain at least one entry");
        }
        Set<String> canonicalPatterns = new LinkedHashSet<>();
        Set<BlockState> matchingStates = new LinkedHashSet<>();
        for (int index = 0; index < inputs.size(); index++) {
            BlockData pattern =
                    parseBlockData(inputs.get(index), "sourceBlockStatePatterns[" + index + "]");
            String canonical = pattern.getAsString(true);
            if (!canonicalPatterns.add(canonical)) {
                throw invalid(
                        "sourceBlockStatePatterns contains a duplicate pattern: " + canonical);
            }
            BlockState parsed = BukkitAdapter.adapt(pattern);
            for (BlockState candidate : parsed.getBlockType().getAllStates()) {
                if (BukkitAdapter.adapt(candidate).matches(pattern)) {
                    matchingStates.add(candidate);
                }
            }
        }
        return new PreparedSources(List.copyOf(canonicalPatterns), List.copyOf(matchingStates));
    }

    private static PreparedPalette preparePalette(List<DestinationPaletteEntry> inputs, int seed)
            throws OperationException {
        if (inputs == null || inputs.isEmpty()) {
            throw invalid("destinationPalette must contain at least one entry");
        }
        List<DestinationPaletteEntry> canonical = new ArrayList<>(inputs.size());
        Set<String> statesSeen = new LinkedHashSet<>();
        List<BlockPalette.WeightedValue<BlockState>> weightedStates =
                new ArrayList<>(inputs.size());
        boolean weighted = false;
        boolean unweighted = false;
        int totalWeight = 0;
        for (int index = 0; index < inputs.size(); index++) {
            DestinationPaletteEntry entry = inputs.get(index);
            if (entry == null) {
                throw invalid("destinationPalette[" + index + "] must contain a blockState");
            }
            BlockData data =
                    parseBlockData(
                            entry.blockState(), "destinationPalette[" + index + "].blockState");
            String canonicalState = data.getAsString();
            if (!statesSeen.add(canonicalState)) {
                throw invalid(
                        "destinationPalette contains a duplicate block state: " + canonicalState);
            }
            Integer weight = entry.weight();
            if (weight == null) {
                unweighted = true;
            } else {
                if (weight < 1 || weight > 100) {
                    throw invalid(
                            "destinationPalette[" + index + "].weight must be between 1 and 100");
                }
                weighted = true;
                totalWeight = Math.addExact(totalWeight, weight);
            }
            canonical.add(new DestinationPaletteEntry(canonicalState, weight));
            weightedStates.add(
                    new BlockPalette.WeightedValue<>(
                            BukkitAdapter.adapt(data), weight == null ? 1 : weight));
        }
        if (weighted && unweighted) {
            throw invalid(
                    "destinationPalette weights must be provided for every entry or omitted from"
                            + " every entry");
        }
        if (weighted && totalWeight != 100) {
            throw invalid("destinationPalette weights must total 100");
        }

        BlockPalette<BlockState> palette = new BlockPalette<>(weightedStates, seed);
        RandomPattern pattern = new RandomPattern(new PaletteRandom(palette));
        for (BlockPalette.WeightedValue<BlockState> state : weightedStates) {
            pattern.add(state.value(), state.weight());
        }
        return new PreparedPalette(List.copyOf(canonical), pattern);
    }

    private static BlockData parseBlockData(String input, String field) throws OperationException {
        if (input == null || input.isBlank()) {
            throw invalid(field + " must be a non-empty string");
        }
        try {
            return Bukkit.createBlockData(input);
        } catch (IllegalArgumentException exception) {
            throw new OperationException(
                    OperationFailure.INVALID_REQUEST,
                    field + " is not a valid block state",
                    exception);
        }
    }

    private <T> T onMainThread(MainThread.CheckedSupplier<T> action) throws OperationException {
        try {
            return this.mainThread.call(action);
        } catch (PaperMainThreadException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof OperationException operationException) {
                    throw operationException;
                }
            }
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE,
                    "Could not access the world on Paper's main thread",
                    exception);
        }
    }

    private void requireAvailable(PaperWorld world) throws OperationException {
        if (this.plugin.getServer().getWorld(world.id()) != world.bukkitWorld()) {
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE,
                    "World is no longer available: " + world.name());
        }
    }

    private static void requireValidHeight(World world, Cuboid region) throws OperationException {
        if (region.min().y() < world.getMinHeight() || region.max().y() >= world.getMaxHeight()) {
            throw invalid(
                    "Y bounds must be between "
                            + world.getMinHeight()
                            + " and "
                            + (world.getMaxHeight() - 1));
        }
    }

    private static List<ChunkPosition> chunks(Cuboid region) {
        int minX = region.min().x() >> 4;
        int maxX = region.max().x() >> 4;
        int minZ = region.min().z() >> 4;
        int maxZ = region.max().z() >> 4;
        List<ChunkPosition> chunks = new ArrayList<>();
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                chunks.add(new ChunkPosition(chunkX, chunkZ));
            }
        }
        return List.copyOf(chunks);
    }

    private static OperationException invalid(String message) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message);
    }

    record PaperWorld(
            UUID id,
            String name,
            World bukkitWorld,
            com.sk89q.worldedit.world.World worldEditWorld,
            JavaPlugin plugin)
            implements EditPlatform.WorldHandle, ChunkTicketManager.TicketWorld {
        @Override
        public boolean isChunkLoaded(ChunkPosition chunk) {
            return this.bukkitWorld.isChunkLoaded(chunk.x(), chunk.z());
        }

        @Override
        public boolean addTicket(ChunkPosition chunk) {
            return this.bukkitWorld.addPluginChunkTicket(chunk.x(), chunk.z(), this.plugin);
        }

        @Override
        public void removeTicket(ChunkPosition chunk) {
            this.bukkitWorld.removePluginChunkTicket(chunk.x(), chunk.z(), this.plugin);
        }
    }

    record PreparedSources(List<String> patterns, List<BlockState> blockStates) {}

    record PreparedPalette(List<DestinationPaletteEntry> entries, Pattern pattern) {}

    record PreparedBlockChange(BlockVector3 position, BlockState blockState) {}

    record PreparedReplace(
            PaperWorld paperWorld,
            PreparedSources sources,
            PreparedPalette palette,
            List<ChunkPosition> chunks,
            ChunkTicketManager.Lease lease)
            implements EditPlatform.PreparedReplace {
        @Override
        public EditPlatform.WorldHandle world() {
            return this.paperWorld;
        }

        @Override
        public List<String> sourcePatterns() {
            return this.sources.patterns();
        }

        @Override
        public List<DestinationPaletteEntry> destinationPalette() {
            return this.palette.entries();
        }

        @Override
        public void close() {
            this.lease.close();
        }
    }

    record PreparedFill(
            PaperWorld paperWorld,
            PreparedPalette palette,
            List<ChunkPosition> chunks,
            ChunkTicketManager.Lease lease)
            implements EditPlatform.PreparedFill {
        @Override
        public EditPlatform.WorldHandle world() {
            return this.paperWorld;
        }

        @Override
        public List<DestinationPaletteEntry> destinationPalette() {
            return this.palette.entries();
        }

        @Override
        public void close() {
            this.lease.close();
        }
    }

    record PreparedSet(
            PaperWorld paperWorld,
            List<PreparedBlockChange> changes,
            List<ChunkPosition> chunks,
            ChunkTicketManager.Lease lease)
            implements EditPlatform.PreparedSet {
        @Override
        public EditPlatform.WorldHandle world() {
            return this.paperWorld;
        }

        @Override
        public int blockCount() {
            return this.changes.size();
        }

        @Override
        public void close() {
            this.lease.close();
        }
    }

    private static final class PaletteRandom implements SimpleRandom {
        private final BlockPalette<?> palette;

        private PaletteRandom(BlockPalette<?> palette) {
            this.palette = palette;
        }

        @Override
        public double nextDouble(int x, int y, int z) {
            return this.palette.randomAt(x, y, z);
        }
    }
}
