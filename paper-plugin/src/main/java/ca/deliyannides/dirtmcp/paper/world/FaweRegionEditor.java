package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionEditor.EditException;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillRegionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillRegionResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRegionBlocksRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRegionBlocksResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoLastDirtEditRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoLastDirtEditResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Bounds;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.NormalizedRegion;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.RegionTooLargeException;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
            JavaPlugin plugin,
            int maxRegionVolume,
            int maxChangedBlocks,
            int undoHistoryPerWorld) {
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
        WorldState state = this.worldStates.computeIfAbsent(world.worldName(), ignored -> new WorldState());
        if (!state.lock.tryLock()) {
            throw new EditException(Failure.WORLD_BUSY, "Another Dirt MCP edit is running in world: "
                    + world.worldName());
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
        WorldState state = this.worldStates.computeIfAbsent(world.worldName(), ignored -> new WorldState());
        if (!state.lock.tryLock()) {
            throw new EditException(Failure.WORLD_BUSY, "Another Dirt MCP edit is running in world: "
                    + world.worldName());
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
    public UndoLastDirtEditResult undoLastDirtEdit(UndoLastDirtEditRequest request)
            throws EditException {
        PreparedWorld prepared = prepareWorld(request.world());
        WorldState state = this.worldStates.computeIfAbsent(prepared.worldName(), ignored -> new WorldState());
        if (!state.lock.tryLock()) {
            throw new EditException(Failure.WORLD_BUSY, "Another Dirt MCP edit is running in world: "
                    + prepared.worldName());
        }

        try {
            EditSession edit = state.history.peekLast();
            if (edit == null) {
                throw new EditException(Failure.NOTHING_TO_UNDO, "No Dirt MCP edit is available to undo");
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
            WorldState state) throws EditException {
        CuboidRegion selection = selection(prepared.world(), region);

        boolean changesBlocks = !prepared.sourceBlockStateName()
                .equals(prepared.destinationBlockStateName());
        EditSession session = newEditSession(prepared.world(), !request.dryRun());
        int matches;
        long changes;
        try (session) {
            matches = session.countBlocks(selection, Set.of(prepared.sourceBlockState()));
            long expectedChanges = changesBlocks ? matches : 0;
            enforceChangeLimit(expectedChanges);

            changes = expectedChanges;
            if (!request.dryRun() && expectedChanges > 0) {
                changes = session.replaceBlocks(
                        selection,
                        Set.of(prepared.sourceBlockState()),
                        prepared.destinationBlockState());
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
            WorldState state) throws EditException {
        CuboidRegion selection = selection(prepared.world(), region);
        EditSession session = newEditSession(prepared.world(), !request.dryRun());
        long changes;
        try (session) {
            int matchingDestination = session.countBlocks(selection, Set.of(prepared.blockState()));
            long expectedChanges = region.volume() - matchingDestination;
            enforceChangeLimit(expectedChanges);

            changes = expectedChanges;
            if (!request.dryRun() && expectedChanges > 0) {
                changes = session.setBlocks((Region) selection, prepared.blockState());
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
                prepared.blockStateName(),
                request.dryRun(),
                region.volume(),
                changes);
    }

    private static CuboidRegion selection(
            com.sk89q.worldedit.world.World world,
            NormalizedRegion region) {
        return new CuboidRegion(
                world,
                com.sk89q.worldedit.math.BlockVector3.at(
                        region.min().x(), region.min().y(), region.min().z()),
                com.sk89q.worldedit.math.BlockVector3.at(
                        region.max().x(), region.max().y(), region.max().z()));
    }

    private EditSession newEditSession(com.sk89q.worldedit.world.World world, boolean recordHistory) {
        var builder = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(world)
                .maxBlocks(this.maxChangedBlocks)
                .allowedRegionsEverywhere();
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
                prepared.sourceBlockStateName(),
                prepared.destinationBlockStateName(),
                request.dryRun(),
                matches,
                changes);
    }

    private NormalizedRegion normalize(
            BlockPosition min,
            BlockPosition max) throws EditException {
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

    private PreparedFill prepare(FillRegionRequest request, NormalizedRegion region) throws EditException {
        return onMainThread(() -> prepareOnMainThread(request, region));
    }

    private PreparedWorld prepareWorld(String worldName) throws EditException {
        return onMainThread(() -> resolveWorld(worldName));
    }

    private <T> T onMainThread(Callable<T> action) throws EditException {
        Future<T> result = this.plugin.getServer().getScheduler().callSyncMethod(this.plugin, action);
        try {
            return result.get();
        } catch (InterruptedException exception) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw new EditException(Failure.WORLD_UNAVAILABLE, "World operation was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof EditException editException) {
                throw editException;
            }
            throw new IllegalStateException("Could not access the world", exception.getCause());
        }
    }

    private PreparedEdit prepareOnMainThread(ReplaceRegionBlocksRequest request, NormalizedRegion region)
            throws EditException {
        PreparedWorld prepared = resolveWorld(request.world());
        World world = prepared.bukkitWorld();
        requireValidHeight(world, region);

        BlockData sourceBlockState = parseBlockData(request.sourceBlockState(), "sourceBlockState");
        BlockData destinationBlockState = parseBlockData(
                request.destinationBlockState(), "destinationBlockState");
        ChunkTickets chunkTickets = retainLoadedChunks(world, region);
        return new PreparedEdit(
                prepared.worldName(),
                prepared.world(),
                BukkitAdapter.adapt(sourceBlockState).toBaseBlock(),
                BukkitAdapter.adapt(destinationBlockState).toBaseBlock(),
                sourceBlockState.getAsString(),
                destinationBlockState.getAsString(),
                chunkTickets);
    }

    private PreparedFill prepareOnMainThread(FillRegionRequest request, NormalizedRegion region)
            throws EditException {
        PreparedWorld prepared = resolveWorld(request.world());
        World world = prepared.bukkitWorld();
        requireValidHeight(world, region);

        BlockData blockState = parseBlockData(request.blockState(), "blockState");
        ChunkTickets chunkTickets = retainLoadedChunks(world, region);
        return new PreparedFill(
                prepared.worldName(),
                prepared.world(),
                BukkitAdapter.adapt(blockState).toBaseBlock(),
                blockState.getAsString(),
                chunkTickets);
    }

    private static void requireValidHeight(World world, NormalizedRegion region) throws EditException {
        if (region.min().y() < world.getMinHeight() || region.max().y() >= world.getMaxHeight()) {
            throw new EditException(
                    Failure.INVALID_REQUEST,
                    "Y bounds must be between " + world.getMinHeight() + " and " + (world.getMaxHeight() - 1));
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
        try {
            return Bukkit.createBlockData(input);
        } catch (IllegalArgumentException exception) {
            throw new EditException(Failure.INVALID_REQUEST, field + " is not a valid block state", exception);
        }
    }

    private ChunkTickets retainLoadedChunks(World world, NormalizedRegion region) throws EditException {
        int minChunkX = region.min().x() >> 4;
        int maxChunkX = region.max().x() >> 4;
        int minChunkZ = region.min().z() >> 4;
        int maxChunkZ = region.max().z() >> 4;
        List<ChunkPosition> chunks = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    throw new EditException(
                            Failure.WORLD_UNAVAILABLE,
                            "Region contains an unloaded chunk at " + chunkX + "," + chunkZ);
                }
                chunks.add(new ChunkPosition(chunkX, chunkZ));
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

        Future<Void> result = this.plugin.getServer().getScheduler().callSyncMethod(this.plugin, () -> {
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
            throw new IllegalStateException("Could not release chunk tickets", exception.getCause());
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
            String worldName,
            World bukkitWorld,
            com.sk89q.worldedit.world.World world) {}

    private record PreparedEdit(
            String worldName,
            com.sk89q.worldedit.world.World world,
            BaseBlock sourceBlockState,
            BaseBlock destinationBlockState,
            String sourceBlockStateName,
            String destinationBlockStateName,
            ChunkTickets chunkTickets) {}

    private record PreparedFill(
            String worldName,
            com.sk89q.worldedit.world.World world,
            BaseBlock blockState,
            String blockStateName,
            ChunkTickets chunkTickets) {}

    private record ChunkTickets(World world, List<ChunkPosition> chunks) {}

    private record ChunkPosition(int x, int z) {}
}
