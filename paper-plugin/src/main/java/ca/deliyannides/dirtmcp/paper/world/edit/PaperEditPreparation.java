package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import com.fastasyncworldedit.core.math.random.SimpleRandom;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.function.pattern.RandomPattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperEditPreparation implements AutoCloseable {
    private final JavaPlugin plugin;
    private final MainThread mainThread;
    private final ChunkTicketManager tickets;
    private final UndoChunkLoader undoChunks;

    PaperEditPreparation(JavaPlugin plugin, MainThread mainThread) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.tickets = new ChunkTicketManager(mainThread);
        this.undoChunks = new UndoChunkLoader(mainThread, this.tickets);
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
                            preparePalette(
                                    request.destinationPalette(),
                                    request.seed(),
                                    "destinationPalette");
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
                            preparePalette(
                                    request.destinationPalette(),
                                    request.seed(),
                                    "destinationPalette");
                    List<ChunkPosition> chunks = chunks(region);
                    ChunkTicketManager.Lease lease = this.tickets.acquire(world, chunks, "Region");
                    return new PreparedFill(world, palette, chunks, lease);
                });
    }

    PreparedSet prepareSet(
            PaperWorld world,
            SetBlocks.Request request,
            List<BlockPosition> resolvedPositions,
            BlockBounds bounds,
            List<ChunkPosition> touchedChunks)
            throws OperationException {
        List<ChunkPosition> chunks = List.copyOf(touchedChunks);
        PreparedSetResources resources =
                onMainThread(
                        () -> {
                            requireAvailable(world);
                            List<PreparedPalette> palettes = prepareSetPalettes(request);
                            requireValidHeight(world.bukkitWorld(), bounds);
                            ChunkTicketManager.Lease lease =
                                    this.tickets.acquire(world, chunks, "Set-blocks edit");
                            try {
                                return new PreparedSetResources(palettes, chunks, lease);
                            } catch (RuntimeException | Error failure) {
                                releaseAfterFailure(lease, failure);
                                throw failure;
                            }
                        });
        return finishPreparedSet(world, request, resolvedPositions, resources);
    }

    ChunkTicketManager.Lease prepareUndo(PaperWorld world, List<ChunkPosition> chunks)
            throws OperationException {
        return this.undoChunks.prepare(world, chunks);
    }

    @Override
    public void close() {
        this.tickets.close();
    }

    static PreparedSet finishPreparedSet(
            PaperWorld world,
            SetBlocks.Request request,
            List<BlockPosition> resolvedPositions,
            PreparedSetResources resources)
            throws OperationException {
        try {
            List<PreparedBlockChange> changes =
                    prepareChanges(request, resolvedPositions, resources.palettes());
            return new PreparedSet(
                    world, resources.palettes(), changes, resources.chunks(), resources.lease());
        } catch (OperationException | RuntimeException | Error failure) {
            releaseAfterFailure(resources.lease(), failure);
            throw failure;
        }
    }

    private static List<PreparedBlockChange> prepareChanges(
            SetBlocks.Request request,
            List<BlockPosition> resolvedPositions,
            List<PreparedPalette> palettes)
            throws OperationException {
        if (resolvedPositions.size() != request.placements().size()) {
            throw new IllegalStateException(
                    "Resolved set-blocks positions do not match the validated request");
        }
        List<PreparedBlockChange> changes = new ArrayList<>(request.placements().size());
        for (int placementIndex = 0;
                placementIndex < request.placements().size();
                placementIndex++) {
            FaweEditExecutor.requireNotInterrupted();
            SetBlocks.Placement placement = request.placements().get(placementIndex);
            Pattern pattern = palettes.get(placement.paletteIndex()).pattern();
            BlockPosition position = resolvedPositions.get(placementIndex);
            BlockVector3 vector = BlockVector3.at(position.x(), position.y(), position.z());
            changes.add(new PreparedBlockChange(vector, pattern));
        }
        return List.copyOf(changes);
    }

    private static void releaseAfterFailure(ChunkTicketManager.Lease lease, Throwable failure) {
        boolean interrupted = Thread.interrupted();
        try {
            lease.close();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static List<PreparedPalette> prepareSetPalettes(SetBlocks.Request request)
            throws OperationException {
        List<PreparedPalette> palettes = new ArrayList<>(request.palettes().size());
        for (int index = 0; index < request.palettes().size(); index++) {
            palettes.add(
                    preparePalette(
                            request.palettes().get(index),
                            request.seed(),
                            "palettes[" + index + "]"));
        }
        return List.copyOf(palettes);
    }

    private static PreparedSources prepareSources(List<String> inputs) throws OperationException {
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

    private static PreparedPalette preparePalette(
            List<DestinationPaletteEntry> inputs, int seed, String field)
            throws OperationException {
        List<DestinationPaletteEntry> canonical = new ArrayList<>(inputs.size());
        Set<String> statesSeen = new LinkedHashSet<>();
        List<WeightedState> weightedStates = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            DestinationPaletteEntry entry = inputs.get(index);
            BlockData data =
                    parseBlockData(entry.blockState(), field + "[" + index + "].blockState");
            String canonicalState = data.getAsString();
            if (!statesSeen.add(canonicalState)) {
                throw invalid(field + " contains a duplicate block state: " + canonicalState);
            }
            Integer weight = entry.weight();
            canonical.add(new DestinationPaletteEntry(canonicalState, weight));
            weightedStates.add(
                    new WeightedState(BukkitAdapter.adapt(data), weight == null ? 1 : weight));
        }
        RandomPattern pattern = new RandomPattern(new PaletteRandom(new CoordinateRandom(seed)));
        for (WeightedState state : weightedStates) {
            pattern.add(state.value(), state.weight());
        }
        return new PreparedPalette(List.copyOf(canonical), pattern);
    }

    private static BlockData parseBlockData(String input, String field) throws OperationException {
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
            if (exception.getCause() instanceof OperationException operationException) {
                throw operationException;
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
        requireValidHeight(world, region.min(), region.max());
    }

    private static void requireValidHeight(World world, BlockBounds bounds)
            throws OperationException {
        requireValidHeight(world, bounds.min(), bounds.max());
    }

    private static void requireValidHeight(
            World world, BlockPosition minimum, BlockPosition maximum) throws OperationException {
        if (minimum.y() < world.getMinHeight() || maximum.y() >= world.getMaxHeight()) {
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
            implements EditPlatform.WorldHandle, UndoChunkLoader.UndoWorld {
        @Override
        public boolean isAvailable() {
            return this.plugin.getServer().getWorld(this.id) == this.bukkitWorld;
        }

        @Override
        public CompletableFuture<Boolean> loadExistingChunk(ChunkPosition chunk) {
            return this.bukkitWorld
                    .getChunkAtAsync(chunk.x(), chunk.z(), false)
                    .thenApply(Objects::nonNull);
        }

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

    record PreparedBlockChange(BlockVector3 position, Pattern pattern) {}

    record PreparedSetResources(
            List<PreparedPalette> palettes,
            List<ChunkPosition> chunks,
            ChunkTicketManager.Lease lease) {
        PreparedSetResources {
            Objects.requireNonNull(palettes, "palettes");
            Objects.requireNonNull(chunks, "chunks");
            Objects.requireNonNull(lease, "lease");
        }
    }

    record PreparedReplace(
            PaperWorld paperWorld,
            PreparedSources sources,
            PreparedPalette palette,
            List<ChunkPosition> chunks,
            ChunkTicketManager.Lease lease)
            implements EditPlatform.PreparedReplace {
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
            List<PreparedPalette> preparedPalettes,
            List<PreparedBlockChange> changes,
            List<ChunkPosition> chunks,
            ChunkTicketManager.Lease lease)
            implements EditPlatform.PreparedSet {
        @Override
        public List<List<DestinationPaletteEntry>> palettes() {
            return this.preparedPalettes.stream().map(PreparedPalette::entries).toList();
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
        private final CoordinateRandom random;

        private PaletteRandom(CoordinateRandom random) {
            this.random = random;
        }

        @Override
        public double nextDouble(int x, int y, int z) {
            return this.random.at(x, y, z);
        }
    }

    private record WeightedState(BlockState value, int weight) {}
}
