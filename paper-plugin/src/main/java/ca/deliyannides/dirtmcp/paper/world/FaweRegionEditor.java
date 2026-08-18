package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionEditor.EditException;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoResult;
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
import java.util.Deque;
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
    private static final int MAX_UNDO_HISTORY = 20;

    private final JavaPlugin plugin;
    private final long maxRegionVolume;
    private final int maxChangedBlocks;
    private final Map<String, WorldState> worldStates = new ConcurrentHashMap<>();

    public FaweRegionEditor(JavaPlugin plugin, long maxRegionVolume, int maxChangedBlocks) {
        if (maxRegionVolume < 1 || maxChangedBlocks < 1) {
            throw new IllegalArgumentException("Edit limits must be positive");
        }
        this.plugin = plugin;
        this.maxRegionVolume = maxRegionVolume;
        this.maxChangedBlocks = maxChangedBlocks;
    }

    @Override
    public ReplaceResult replace(ReplaceRequest request) throws EditException {
        NormalizedRegion region = normalize(request.min(), request.max());
        PreparedEdit prepared = prepare(request, region);
        WorldState state = this.worldStates.computeIfAbsent(prepared.worldName(), ignored -> new WorldState());
        if (!state.lock.tryLock()) {
            throw new EditException(Failure.WORLD_BUSY, "Another Dirt MCP edit is running in world: "
                    + prepared.worldName());
        }

        try {
            return replaceLocked(request, region, prepared, state);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public FillResult fill(FillRequest request) throws EditException {
        NormalizedRegion region = normalize(request.min(), request.max());
        PreparedFill prepared = prepare(request, region);
        WorldState state = this.worldStates.computeIfAbsent(prepared.worldName(), ignored -> new WorldState());
        if (!state.lock.tryLock()) {
            throw new EditException(Failure.WORLD_BUSY, "Another Dirt MCP edit is running in world: "
                    + prepared.worldName());
        }

        try {
            return fillLocked(request, region, prepared, state);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public UndoResult undo(UndoRequest request) throws EditException {
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
            long changedBlocks = edit.getChangeSet().longSize();
            try (EditSession undoSession = newEditSession(prepared.world(), false)) {
                edit.undo(undoSession);
            }
            state.history.removeLast();
            return new UndoResult(prepared.worldName(), changedBlocks);
        } finally {
            state.lock.unlock();
        }
    }

    private ReplaceResult replaceLocked(
            ReplaceRequest request,
            NormalizedRegion region,
            PreparedEdit prepared,
            WorldState state) throws EditException {
        CuboidRegion selection = selection(prepared.world(), region);

        boolean changesBlocks = !prepared.sourceName().equals(prepared.destinationName());
        EditSession session = newEditSession(prepared.world(), !request.dryRun());
        int matches;
        long changes;
        try (session) {
            matches = session.countBlocks(selection, Set.of(prepared.source()));
            long expectedChanges = changesBlocks ? matches : 0;
            enforceChangeLimit(expectedChanges);

            changes = expectedChanges;
            if (!request.dryRun() && expectedChanges > 0) {
                changes = session.replaceBlocks(selection, Set.of(prepared.source()), prepared.destination());
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

    private FillResult fillLocked(
            FillRequest request,
            NormalizedRegion region,
            PreparedFill prepared,
            WorldState state) throws EditException {
        CuboidRegion selection = selection(prepared.world(), region);
        EditSession session = newEditSession(prepared.world(), !request.dryRun());
        long changes;
        try (session) {
            int matchingDestination = session.countBlocks(selection, Set.of(prepared.destination()));
            long expectedChanges = region.volume() - matchingDestination;
            enforceChangeLimit(expectedChanges);

            changes = expectedChanges;
            if (!request.dryRun() && expectedChanges > 0) {
                changes = session.setBlocks((Region) selection, prepared.destination());
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
        return new FillResult(
                prepared.worldName(),
                new Bounds(region.min(), region.max()),
                prepared.destinationName(),
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

    private ReplaceResult result(
            ReplaceRequest request,
            NormalizedRegion region,
            PreparedEdit prepared,
            long matches,
            long changes) {
        return new ReplaceResult(
                prepared.worldName(),
                new Bounds(region.min(), region.max()),
                prepared.sourceName(),
                prepared.destinationName(),
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

    private PreparedEdit prepare(ReplaceRequest request, NormalizedRegion region) throws EditException {
        return onMainThread(() -> prepareOnMainThread(request, region));
    }

    private PreparedFill prepare(FillRequest request, NormalizedRegion region) throws EditException {
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

    private PreparedEdit prepareOnMainThread(ReplaceRequest request, NormalizedRegion region)
            throws EditException {
        PreparedWorld prepared = resolveWorld(request.world());
        World world = prepared.bukkitWorld();
        requireValidHeight(world, region);
        requireLoadedChunks(world, region);

        BlockData source = parseBlockData(request.source(), "source");
        BlockData destination = parseBlockData(request.destination(), "destination");
        return new PreparedEdit(
                prepared.worldName(),
                prepared.world(),
                BukkitAdapter.adapt(source).toBaseBlock(),
                BukkitAdapter.adapt(destination).toBaseBlock(),
                source.getAsString(),
                destination.getAsString());
    }

    private PreparedFill prepareOnMainThread(FillRequest request, NormalizedRegion region)
            throws EditException {
        PreparedWorld prepared = resolveWorld(request.world());
        World world = prepared.bukkitWorld();
        requireValidHeight(world, region);
        requireLoadedChunks(world, region);

        BlockData destination = parseBlockData(request.destination(), "destination");
        return new PreparedFill(
                prepared.worldName(),
                prepared.world(),
                BukkitAdapter.adapt(destination).toBaseBlock(),
                destination.getAsString());
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

    private static void requireLoadedChunks(World world, NormalizedRegion region) throws EditException {
        int minChunkX = region.min().x() >> 4;
        int maxChunkX = region.max().x() >> 4;
        int minChunkZ = region.min().z() >> 4;
        int maxChunkZ = region.max().z() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    throw new EditException(
                            Failure.WORLD_UNAVAILABLE,
                            "Region contains an unloaded chunk at " + chunkX + "," + chunkZ);
                }
            }
        }
    }

    private void enforceChangeLimit(long changes) throws EditException {
        if (changes > this.maxChangedBlocks) {
            throw new EditException(
                    Failure.CHANGE_LIMIT_EXCEEDED,
                    "Edit exceeds the maximum of " + this.maxChangedBlocks + " changed blocks");
        }
    }

    private static void remember(WorldState state, EditSession session) {
        state.history.addLast(session);
        while (state.history.size() > MAX_UNDO_HISTORY) {
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
            BaseBlock source,
            BaseBlock destination,
            String sourceName,
            String destinationName) {}

    private record PreparedFill(
            String worldName,
            com.sk89q.worldedit.world.World world,
            BaseBlock destination,
            String destinationName) {}
}
