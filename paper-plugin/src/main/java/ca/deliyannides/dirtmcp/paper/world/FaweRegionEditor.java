package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionEditor.BlockChange;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.DestinationPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.EditException;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillRegionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillRegionResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRegionBlocksRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRegionBlocksResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.SetBlocksRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.SetBlocksResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoLastDirtEditRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoLastDirtEditResult;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.NormalizedRegion;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.RegionTooLargeException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Bounds;
import com.fastasyncworldedit.core.math.random.SimpleRandom;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.mask.BlockMask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.function.pattern.RandomPattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

public final class FaweRegionEditor implements RegionEditor {
    private final JavaPlugin plugin;
    private final int maxRegionVolume;
    private final int maxChangedBlocks;
    private final int undoHistoryPerWorld;
    private final Map<String, WorldState> worldStates = new ConcurrentHashMap<>();

    public FaweRegionEditor(
            JavaPlugin plugin, int maxRegionVolume, int maxChangedBlocks, int undoHistoryPerWorld) {
        if (maxRegionVolume < 1 || maxChangedBlocks < 1 || undoHistoryPerWorld < 0) {
            throw new IllegalArgumentException(
                    "Edit limits must be positive and undo history must be non-negative");
        }
        this.plugin = plugin;
        this.maxRegionVolume = maxRegionVolume;
        this.maxChangedBlocks = maxChangedBlocks;
        this.undoHistoryPerWorld = undoHistoryPerWorld;
    }

    @Override
    public ReplaceRegionBlocksResult replaceRegionBlocks(ReplaceRegionBlocksRequest request)
            throws EditException {
        NormalizedRegion region = normalize(request.min(), request.max());
        PreparedWorld world = prepareWorld(request.world());
        WorldState state =
                this.worldStates.computeIfAbsent(world.worldName(), ignored -> new WorldState());
        if (!state.lock.tryLock()) {
            throw new EditException(
                    Failure.WORLD_BUSY,
                    "Another Dirt MCP edit is running in world: " + world.worldName());
        }

        try {
            PreparedEdit prepared = prepare(request, region);
            try {
                return replaceLocked(request, region, prepared, state);
            } finally {
                releaseChunkTickets(prepared.chunkTickets());
            }
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public FillRegionResult fillRegion(FillRegionRequest request) throws EditException {
        NormalizedRegion region = normalize(request.min(), request.max());
        PreparedWorld world = prepareWorld(request.world());
        WorldState state =
                this.worldStates.computeIfAbsent(world.worldName(), ignored -> new WorldState());
        if (!state.lock.tryLock()) {
            throw new EditException(
                    Failure.WORLD_BUSY,
                    "Another Dirt MCP edit is running in world: " + world.worldName());
        }

        try {
            PreparedFill prepared = prepare(request, region);
            try {
                return fillLocked(request, region, prepared, state);
            } finally {
                releaseChunkTickets(prepared.chunkTickets());
            }
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public SetBlocksResult setBlocks(SetBlocksRequest request) throws EditException {
        validateSparseSize(request);
        PreparedWorld world = prepareWorld(request.world());
        WorldState state =
                this.worldStates.computeIfAbsent(world.worldName(), ignored -> new WorldState());
        if (!state.lock.tryLock()) {
            throw new EditException(
                    Failure.WORLD_BUSY,
                    "Another Dirt MCP edit is running in world: " + world.worldName());
        }

        try {
            PreparedSparseEdit prepared = prepare(request, world);
            try {
                return setBlocksLocked(request, prepared, state);
            } finally {
                releaseChunkTickets(prepared.chunkTickets());
            }
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public UndoLastDirtEditResult undoLastDirtEdit(UndoLastDirtEditRequest request)
            throws EditException {
        PreparedWorld prepared = prepareWorld(request.world());
        WorldState state =
                this.worldStates.computeIfAbsent(prepared.worldName(), ignored -> new WorldState());
        if (!state.lock.tryLock()) {
            throw new EditException(
                    Failure.WORLD_BUSY,
                    "Another Dirt MCP edit is running in world: " + prepared.worldName());
        }

        try {
            EditSession edit = state.history.peekLast();
            if (edit == null) {
                throw new EditException(
                        Failure.NOTHING_TO_UNDO, "No Dirt MCP edit is available to undo");
            }
            long changedBlockCount = edit.getChangeSet().longSize();
            try (EditSession undoSession = newEditSession(prepared.world(), false)) {
                edit.undo(undoSession);
            }
            state.history.removeLast();
            return new UndoLastDirtEditResult(prepared.worldName(), changedBlockCount);
        } finally {
            state.lock.unlock();
        }
    }

    private ReplaceRegionBlocksResult replaceLocked(
            ReplaceRegionBlocksRequest request,
            NormalizedRegion region,
            PreparedEdit prepared,
            WorldState state)
            throws EditException {
        CuboidRegion selection = selection(prepared.world(), region);

        EditSession session = newEditSession(prepared.world(), !request.dryRun());
        long matches = 0;
        long expectedChanges = 0;
        long changes;
        try (session) {
            BlockMask sourceMask = new BlockMask(session);
            sourceMask.add(prepared.sourceBlockStates().toArray(BlockState[]::new));
            for (BlockVector3 position : selection) {
                BlockState current = session.getBlock(position);
                if (!sourceMask.test(current)) {
                    continue;
                }
                matches++;
                if (!current.equals(destinationAt(prepared.destinationPalette(), position))) {
                    expectedChanges++;
                }
            }
            enforceChangeLimit(expectedChanges);

            changes = expectedChanges;
            if (!request.dryRun() && expectedChanges > 0) {
                session.replaceBlocks(
                        selection, sourceMask, prepared.destinationPalette().pattern());
                changes = session.getChangeSet().longSize();
            }
        } catch (MaxChangedBlocksException exception) {
            throw new EditException(
                    Failure.CHANGE_LIMIT_EXCEEDED,
                    "Edit exceeds the maximum of " + this.maxChangedBlocks + " changed blocks",
                    exception);
        }
        if (!request.dryRun() && changes > 0) {
            remember(state, session);
        }
        return result(request, region, prepared, matches, changes);
    }

    private FillRegionResult fillLocked(
            FillRegionRequest request,
            NormalizedRegion region,
            PreparedFill prepared,
            WorldState state)
            throws EditException {
        CuboidRegion selection = selection(prepared.world(), region);
        EditSession session = newEditSession(prepared.world(), !request.dryRun());
        long expectedChanges = 0;
        long changes;
        try (session) {
            for (BlockVector3 position : selection) {
                if (!session.getBlock(position)
                        .equals(destinationAt(prepared.destinationPalette(), position))) {
                    expectedChanges++;
                }
            }
            enforceChangeLimit(expectedChanges);

            changes = expectedChanges;
            if (!request.dryRun() && expectedChanges > 0) {
                session.setBlocks(
                        (com.sk89q.worldedit.regions.Region) selection,
                        prepared.destinationPalette().pattern());
                changes = session.getChangeSet().longSize();
            }
        } catch (MaxChangedBlocksException exception) {
            throw new EditException(
                    Failure.CHANGE_LIMIT_EXCEEDED,
                    "Edit exceeds the maximum of " + this.maxChangedBlocks + " changed blocks",
                    exception);
        }
        if (!request.dryRun() && changes > 0) {
            remember(state, session);
        }
        return new FillRegionResult(
                prepared.worldName(),
                new Bounds(region.min(), region.max()),
                prepared.destinationPalette().entries(),
                request.seed(),
                request.dryRun(),
                region.volume(),
                changes);
    }

    private SetBlocksResult setBlocksLocked(
            SetBlocksRequest request, PreparedSparseEdit prepared, WorldState state)
            throws EditException {
        EditSession session = newEditSession(prepared.world(), !request.dryRun());
        List<PreparedBlockChange> pendingChanges = new ArrayList<>();
        long expectedChanges;
        try (session) {
            for (PreparedBlockChange change : prepared.changes()) {
                if (!session.getBlock(change.position()).equals(change.blockState())) {
                    pendingChanges.add(change);
                }
            }
            expectedChanges = pendingChanges.size();
            enforceChangeLimit(expectedChanges);

            if (!request.dryRun() && expectedChanges > 0) {
                for (PreparedBlockChange change : pendingChanges) {
                    session.setBlock(
                            change.position().x(),
                            change.position().y(),
                            change.position().z(),
                            change.blockState());
                }
            }
        }
        long changes = request.dryRun() ? expectedChanges : session.getChangeSet().longSize();
        if (!request.dryRun() && changes > 0) {
            remember(state, session);
        }
        return new SetBlocksResult(
                prepared.worldName(),
                request.dryRun(),
                prepared.changes().size(),
                changes,
                prepared.changes().size() - changes);
    }

    private static CuboidRegion selection(
            com.sk89q.worldedit.world.World world, NormalizedRegion region) {
        return new CuboidRegion(
                world,
                com.sk89q.worldedit.math.BlockVector3.at(
                        region.min().x(), region.min().y(), region.min().z()),
                com.sk89q.worldedit.math.BlockVector3.at(
                        region.max().x(), region.max().y(), region.max().z()));
    }

    private EditSession newEditSession(
            com.sk89q.worldedit.world.World world, boolean recordHistory) {
        var builder =
                WorldEdit.getInstance()
                        .newEditSessionBuilder()
                        .world(world)
                        .maxBlocks(this.maxChangedBlocks)
                        .allowedRegionsEverywhere()
                        .setSideEffectSet(SideEffectSet.api().without(SideEffect.NEIGHBORS));
        if (recordHistory) {
            return builder.fastMode(false).combineStages(true).changeSet(false, null).build();
        }
        return builder.fastMode(true).changeSetNull().build();
    }

    private ReplaceRegionBlocksResult result(
            ReplaceRegionBlocksRequest request,
            NormalizedRegion region,
            PreparedEdit prepared,
            long matches,
            long changes) {
        return new ReplaceRegionBlocksResult(
                prepared.worldName(),
                new Bounds(region.min(), region.max()),
                prepared.sourceBlockStatePatterns(),
                prepared.destinationPalette().entries(),
                request.seed(),
                request.dryRun(),
                matches,
                changes);
    }

    private NormalizedRegion normalize(BlockPosition min, BlockPosition max) throws EditException {
        try {
            return RegionGeometry.normalize(min, max, this.maxRegionVolume);
        } catch (RegionTooLargeException exception) {
            throw new EditException(Failure.REGION_TOO_LARGE, exception.getMessage(), exception);
        }
    }

    private PreparedEdit prepare(ReplaceRegionBlocksRequest request, NormalizedRegion region)
            throws EditException {
        return onMainThread(() -> prepareOnMainThread(request, region));
    }

    private PreparedFill prepare(FillRegionRequest request, NormalizedRegion region)
            throws EditException {
        return onMainThread(() -> prepareOnMainThread(request, region));
    }

    private PreparedSparseEdit prepare(SetBlocksRequest request, PreparedWorld world)
            throws EditException {
        return onMainThread(() -> prepareOnMainThread(request, world));
    }

    private PreparedWorld prepareWorld(String worldName) throws EditException {
        return onMainThread(() -> resolveWorld(worldName));
    }

    private <T> T onMainThread(Callable<T> action) throws EditException {
        Future<T> result =
                this.plugin.getServer().getScheduler().callSyncMethod(this.plugin, action);
        try {
            return result.get();
        } catch (InterruptedException exception) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw new EditException(
                    Failure.WORLD_UNAVAILABLE, "World operation was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof EditException editException) {
                throw editException;
            }
            throw new IllegalStateException("Could not access the world", exception.getCause());
        }
    }

    private PreparedEdit prepareOnMainThread(
            ReplaceRegionBlocksRequest request, NormalizedRegion region) throws EditException {
        PreparedWorld prepared = resolveWorld(request.world());
        World world = prepared.bukkitWorld();
        requireValidHeight(world, region);

        PreparedSourcePatterns sourcePatterns =
                prepareSourcePatterns(request.sourceBlockStatePatterns());
        PreparedDestinationPalette destinationPalette =
                prepareDestinationPalette(request.destinationPalette(), request.seed());
        ChunkTickets chunkTickets = retainLoadedChunks(world, region);
        return new PreparedEdit(
                prepared.worldName(),
                prepared.world(),
                sourcePatterns.blockStates(),
                sourcePatterns.patterns(),
                destinationPalette,
                chunkTickets);
    }

    private PreparedFill prepareOnMainThread(FillRegionRequest request, NormalizedRegion region)
            throws EditException {
        PreparedWorld prepared = resolveWorld(request.world());
        World world = prepared.bukkitWorld();
        requireValidHeight(world, region);

        PreparedDestinationPalette destinationPalette =
                prepareDestinationPalette(request.destinationPalette(), request.seed());
        ChunkTickets chunkTickets = retainLoadedChunks(world, region);
        return new PreparedFill(
                prepared.worldName(), prepared.world(), destinationPalette, chunkTickets);
    }

    private PreparedSparseEdit prepareOnMainThread(SetBlocksRequest request, PreparedWorld prepared)
            throws EditException {
        World world = prepared.bukkitWorld();
        Map<BlockPosition, Integer> positions = new HashMap<>();
        Map<String, BlockState> blockStates = new HashMap<>();
        List<PreparedBlockChange> changes = new ArrayList<>(request.changes().size());

        for (int index = 0; index < request.changes().size(); index++) {
            BlockChange change = request.changes().get(index);
            if (change == null || change.position() == null) {
                throw new EditException(
                        Failure.INVALID_REQUEST,
                        "changes[" + index + "] must contain a position and blockState");
            }
            BlockPosition position = change.position();
            if (position.y() < world.getMinHeight() || position.y() >= world.getMaxHeight()) {
                throw new EditException(
                        Failure.INVALID_REQUEST,
                        "changes["
                                + index
                                + "].position.y must be between "
                                + world.getMinHeight()
                                + " and "
                                + (world.getMaxHeight() - 1));
            }
            Integer previous = positions.putIfAbsent(position, index);
            if (previous != null) {
                throw new EditException(
                        Failure.INVALID_REQUEST,
                        "changes["
                                + index
                                + "].position duplicates changes["
                                + previous
                                + "].position");
            }
            if (change.blockState() == null || change.blockState().isBlank()) {
                throw new EditException(
                        Failure.INVALID_REQUEST,
                        "changes[" + index + "].blockState must be a non-empty string");
            }
            BlockState state = blockStates.get(change.blockState());
            if (state == null) {
                BlockData blockData =
                        parseBlockData(change.blockState(), "changes[" + index + "].blockState");
                state = BukkitAdapter.adapt(blockData);
                blockStates.put(change.blockState(), state);
            }
            changes.add(
                    new PreparedBlockChange(
                            BlockVector3.at(position.x(), position.y(), position.z()), state));
        }

        ChunkTickets chunkTickets =
                retainLoadedChunks(
                        world, request.changes().stream().map(BlockChange::position).toList());
        return new PreparedSparseEdit(
                prepared.worldName(), prepared.world(), List.copyOf(changes), chunkTickets);
    }

    private void validateSparseSize(SetBlocksRequest request) throws EditException {
        if (request == null || request.changes() == null || request.changes().isEmpty()) {
            throw new EditException(
                    Failure.INVALID_REQUEST, "changes must contain at least one block change");
        }
        if (request.changes().size() > this.maxRegionVolume) {
            throw new EditException(
                    Failure.REGION_TOO_LARGE,
                    "Sparse edit contains "
                            + request.changes().size()
                            + " blocks; maximum is "
                            + this.maxRegionVolume);
        }
    }

    private static void requireValidHeight(World world, NormalizedRegion region)
            throws EditException {
        if (region.min().y() < world.getMinHeight() || region.max().y() >= world.getMaxHeight()) {
            throw new EditException(
                    Failure.INVALID_REQUEST,
                    "Y bounds must be between "
                            + world.getMinHeight()
                            + " and "
                            + (world.getMaxHeight() - 1));
        }
    }

    private PreparedWorld resolveWorld(String worldName) throws EditException {
        World world = this.plugin.getServer().getWorld(worldName);
        if (world == null) {
            throw new EditException(Failure.WORLD_NOT_FOUND, "World is not loaded: " + worldName);
        }
        return new PreparedWorld(world.getName(), world, BukkitAdapter.adapt(world));
    }

    private static BlockData parseBlockData(String input, String field) throws EditException {
        if (input == null || input.isBlank()) {
            throw new EditException(Failure.INVALID_REQUEST, field + " must be a non-empty string");
        }
        try {
            return Bukkit.createBlockData(input);
        } catch (IllegalArgumentException exception) {
            throw new EditException(
                    Failure.INVALID_REQUEST, field + " is not a valid block state", exception);
        }
    }

    private static PreparedSourcePatterns prepareSourcePatterns(List<String> inputs)
            throws EditException {
        if (inputs == null || inputs.isEmpty()) {
            throw new EditException(
                    Failure.INVALID_REQUEST,
                    "sourceBlockStatePatterns must contain at least one entry");
        }
        Set<String> canonicalPatterns = new LinkedHashSet<>();
        Set<BlockState> matchingStates = new LinkedHashSet<>();
        for (int index = 0; index < inputs.size(); index++) {
            BlockData pattern =
                    parseBlockData(inputs.get(index), "sourceBlockStatePatterns[" + index + "]");
            String canonicalPattern = pattern.getAsString(true);
            if (!canonicalPatterns.add(canonicalPattern)) {
                throw new EditException(
                        Failure.INVALID_REQUEST,
                        "sourceBlockStatePatterns contains a duplicate pattern: "
                                + canonicalPattern);
            }
            BlockState parsedState = BukkitAdapter.adapt(pattern);
            for (BlockState candidate : parsedState.getBlockType().getAllStates()) {
                if (BukkitAdapter.adapt(candidate).matches(pattern)) {
                    matchingStates.add(candidate);
                }
            }
        }
        return new PreparedSourcePatterns(
                List.copyOf(canonicalPatterns), List.copyOf(matchingStates));
    }

    private static PreparedDestinationPalette prepareDestinationPalette(
            List<DestinationPaletteEntry> inputs, int seed) throws EditException {
        if (inputs == null || inputs.isEmpty()) {
            throw new EditException(
                    Failure.INVALID_REQUEST, "destinationPalette must contain at least one entry");
        }
        List<DestinationPaletteEntry> canonicalEntries = new ArrayList<>(inputs.size());
        Set<String> canonicalStates = new LinkedHashSet<>();
        List<BlockState> blockStates = new ArrayList<>(inputs.size());
        boolean hasWeights = false;
        boolean hasUnweightedEntries = false;
        int weightTotal = 0;
        for (int index = 0; index < inputs.size(); index++) {
            DestinationPaletteEntry input = inputs.get(index);
            if (input == null) {
                throw new EditException(
                        Failure.INVALID_REQUEST,
                        "destinationPalette[" + index + "] must contain a blockState");
            }
            BlockData blockData =
                    parseBlockData(
                            input.blockState(), "destinationPalette[" + index + "].blockState");
            String canonicalState = blockData.getAsString();
            if (!canonicalStates.add(canonicalState)) {
                throw new EditException(
                        Failure.INVALID_REQUEST,
                        "destinationPalette contains a duplicate block state: " + canonicalState);
            }
            Integer weight = input.weight();
            if (weight == null) {
                hasUnweightedEntries = true;
            } else {
                if (weight < 1 || weight > 100) {
                    throw new EditException(
                            Failure.INVALID_REQUEST,
                            "destinationPalette[" + index + "].weight must be between 1 and 100");
                }
                hasWeights = true;
                weightTotal = Math.addExact(weightTotal, weight);
            }
            canonicalEntries.add(new DestinationPaletteEntry(canonicalState, weight));
            blockStates.add(BukkitAdapter.adapt(blockData));
        }
        if (hasWeights && hasUnweightedEntries) {
            throw new EditException(
                    Failure.INVALID_REQUEST,
                    "destinationPalette weights must be provided for every entry or omitted from every entry");
        }
        if (hasWeights && weightTotal != 100) {
            throw new EditException(
                    Failure.INVALID_REQUEST, "destinationPalette weights must total 100");
        }

        RandomPattern pattern = new RandomPattern(new SeededCoordinateRandom(seed));
        for (int index = 0; index < blockStates.size(); index++) {
            Integer weight = canonicalEntries.get(index).weight();
            pattern.add(blockStates.get(index), weight == null ? 1.0 : weight.doubleValue());
        }
        return new PreparedDestinationPalette(List.copyOf(canonicalEntries), pattern);
    }

    private static BlockState destinationAt(
            PreparedDestinationPalette palette, BlockVector3 position) {
        return palette.pattern().applyBlock(position).toBlockState();
    }

    private static final class SeededCoordinateRandom implements SimpleRandom {
        private static final double UNIT_DOUBLE = 0x1.0p-53;
        private final long seed;

        private SeededCoordinateRandom(int seed) {
            this.seed = Integer.toUnsignedLong(seed);
        }

        @Override
        public double nextDouble(int x, int y, int z) {
            long value = mix(this.seed ^ 0x9e3779b97f4a7c15L);
            value = mix(value ^ Integer.toUnsignedLong(x));
            value = mix(value ^ Integer.toUnsignedLong(y));
            value = mix(value ^ Integer.toUnsignedLong(z));
            return (value >>> 11) * UNIT_DOUBLE;
        }

        private static long mix(long value) {
            long mixed = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
            mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
            return mixed ^ (mixed >>> 31);
        }
    }

    private ChunkTickets retainLoadedChunks(World world, NormalizedRegion region)
            throws EditException {
        int minChunkX = region.min().x() >> 4;
        int maxChunkX = region.max().x() >> 4;
        int minChunkZ = region.min().z() >> 4;
        int maxChunkZ = region.max().z() >> 4;
        List<ChunkPosition> chunks = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(new ChunkPosition(chunkX, chunkZ));
            }
        }
        return retainLoadedChunks(world, chunks, "Region");
    }

    private ChunkTickets retainLoadedChunks(World world, List<BlockPosition> positions)
            throws EditException {
        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        for (BlockPosition position : positions) {
            chunks.add(new ChunkPosition(position.x() >> 4, position.z() >> 4));
        }
        return retainLoadedChunks(world, chunks, "Sparse edit");
    }

    private ChunkTickets retainLoadedChunks(
            World world, Iterable<ChunkPosition> requestedChunks, String operation)
            throws EditException {
        Set<ChunkPosition> chunks = new LinkedHashSet<>();
        requestedChunks.forEach(chunks::add);
        for (ChunkPosition chunk : chunks) {
            if (!world.isChunkLoaded(chunk.x(), chunk.z())) {
                throw new EditException(
                        Failure.WORLD_UNAVAILABLE,
                        operation
                                + " contains an unloaded chunk at "
                                + chunk.x()
                                + ","
                                + chunk.z());
            }
        }

        List<ChunkPosition> retained = new ArrayList<>(chunks.size());
        try {
            for (ChunkPosition chunk : chunks) {
                if (world.addPluginChunkTicket(chunk.x(), chunk.z(), this.plugin)) {
                    retained.add(chunk);
                }
            }
        } catch (RuntimeException exception) {
            removeChunkTickets(world, retained);
            throw exception;
        }
        return new ChunkTickets(world, List.copyOf(retained));
    }

    private void releaseChunkTickets(ChunkTickets chunkTickets) {
        if (chunkTickets.chunks().isEmpty()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            removeChunkTickets(chunkTickets.world(), chunkTickets.chunks());
            return;
        }

        Future<Void> result =
                this.plugin
                        .getServer()
                        .getScheduler()
                        .callSyncMethod(
                                this.plugin,
                                () -> {
                                    removeChunkTickets(chunkTickets.world(), chunkTickets.chunks());
                                    return null;
                                });
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    result.get();
                    return;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                    "Could not release chunk tickets", exception.getCause());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void removeChunkTickets(World world, List<ChunkPosition> chunks) {
        for (ChunkPosition chunk : chunks) {
            world.removePluginChunkTicket(chunk.x(), chunk.z(), this.plugin);
        }
    }

    private void enforceChangeLimit(long changes) throws EditException {
        if (changes > this.maxChangedBlocks) {
            throw new EditException(
                    Failure.CHANGE_LIMIT_EXCEEDED,
                    "Edit exceeds the maximum of " + this.maxChangedBlocks + " changed blocks");
        }
    }

    private void remember(WorldState state, EditSession session) {
        state.history.addLast(session);
        while (state.history.size() > this.undoHistoryPerWorld) {
            state.history.removeFirst();
        }
    }

    private static final class WorldState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<EditSession> history = new ArrayDeque<>();
    }

    private record PreparedWorld(
            String worldName, World bukkitWorld, com.sk89q.worldedit.world.World world) {}

    private record PreparedEdit(
            String worldName,
            com.sk89q.worldedit.world.World world,
            List<BlockState> sourceBlockStates,
            List<String> sourceBlockStatePatterns,
            PreparedDestinationPalette destinationPalette,
            ChunkTickets chunkTickets) {}

    private record PreparedFill(
            String worldName,
            com.sk89q.worldedit.world.World world,
            PreparedDestinationPalette destinationPalette,
            ChunkTickets chunkTickets) {}

    private record PreparedSourcePatterns(List<String> patterns, List<BlockState> blockStates) {}

    private record PreparedDestinationPalette(
            List<DestinationPaletteEntry> entries, Pattern pattern) {}

    private record PreparedSparseEdit(
            String worldName,
            com.sk89q.worldedit.world.World world,
            List<PreparedBlockChange> changes,
            ChunkTickets chunkTickets) {}

    private record PreparedBlockChange(BlockVector3 position, BlockState blockState) {}

    private record ChunkTickets(World world, List<ChunkPosition> chunks) {}

    private record ChunkPosition(int x, int z) {}
}
