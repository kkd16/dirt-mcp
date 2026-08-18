package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionEditor.EditException;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Bounds;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.NormalizedRegion;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.RegionTooLargeException;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
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
    private final long maxRegionVolume;
    private final int maxChangedBlocks;
    private final int maxUndoEntries;
    private final Map<String, ReentrantLock> worldLocks = new ConcurrentHashMap<>();
    private final Map<String, Deque<EditSession>> undoHistory = new ConcurrentHashMap<>();

    public FaweRegionEditor(
            JavaPlugin plugin, long maxRegionVolume, int maxChangedBlocks, int maxUndoEntries) {
        if (maxRegionVolume < 1 || maxChangedBlocks < 1 || maxUndoEntries < 1) {
            throw new IllegalArgumentException("Edit limits must be positive");
        }
        this.plugin = plugin;
        this.maxRegionVolume = maxRegionVolume;
        this.maxChangedBlocks = maxChangedBlocks;
        this.maxUndoEntries = maxUndoEntries;
    }

    @Override
    public ReplaceResult replace(ReplaceRequest request) throws EditException {
        NormalizedRegion region = normalize(request);
        PreparedEdit prepared = prepare(request, region);
        ReentrantLock lock = this.worldLocks.computeIfAbsent(prepared.worldName(), ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new EditException(Failure.WORLD_BUSY, "Another Dirt MCP edit is running in world: "
                    + prepared.worldName());
        }

        try {
            return replaceLocked(request, region, prepared);
        } finally {
            lock.unlock();
        }
    }

    private ReplaceResult replaceLocked(
            ReplaceRequest request, NormalizedRegion region, PreparedEdit prepared) throws EditException {
        CuboidRegion selection = new CuboidRegion(
                prepared.world(),
                com.sk89q.worldedit.math.BlockVector3.at(
                        region.min().x(), region.min().y(), region.min().z()),
                com.sk89q.worldedit.math.BlockVector3.at(
                        region.max().x(), region.max().y(), region.max().z()));

        if (request.dryRun()) {
            try (EditSession session = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(prepared.world())
                    .allowedRegionsEverywhere()
                    .fastMode(true)
                    .changeSetNull()
                    .build()) {
                int matches = session.countBlocks(selection, Set.of(prepared.source()));
                long changes = prepared.sourceName().equals(prepared.destinationName()) ? 0 : matches;
                enforceChangeLimit(changes);
                return result(request, region, prepared, matches, changes);
            }
        }

        EditSession session = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(prepared.world())
                .maxBlocks(this.maxChangedBlocks)
                .allowedRegionsEverywhere()
                .fastMode(false)
                .combineStages(false)
                .changeSet(false, null)
                .build();
        int matches;
        int changes = 0;
        try (session) {
            matches = session.countBlocks(selection, Set.of(prepared.source()));
            enforceChangeLimit(prepared.sourceName().equals(prepared.destinationName()) ? 0 : matches);
            if (!prepared.sourceName().equals(prepared.destinationName()) && matches > 0) {
                changes = session.replaceBlocks(selection, Set.of(prepared.source()), prepared.destination());
            }
        } catch (MaxChangedBlocksException exception) {
            throw new EditException(
                    Failure.CHANGE_LIMIT_EXCEEDED,
                    "Edit exceeds the maximum of " + this.maxChangedBlocks + " changed blocks",
                    exception);
        } finally {
            if (session.getChangeSet() != null && session.getChangeSet().longSize() > 0) {
                remember(prepared.worldName(), session);
            }
        }

        return result(request, region, prepared, matches, changes);
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

    private NormalizedRegion normalize(ReplaceRequest request) throws EditException {
        try {
            return RegionGeometry.normalize(request.min(), request.max(), this.maxRegionVolume);
        } catch (RegionTooLargeException exception) {
            throw new EditException(Failure.REGION_TOO_LARGE, exception.getMessage(), exception);
        }
    }

    private PreparedEdit prepare(ReplaceRequest request, NormalizedRegion region) throws EditException {
        Future<PreparedEdit> preparation = this.plugin.getServer()
                .getScheduler()
                .callSyncMethod(this.plugin, () -> prepareOnMainThread(request, region));
        try {
            return preparation.get();
        } catch (InterruptedException exception) {
            preparation.cancel(false);
            Thread.currentThread().interrupt();
            throw new EditException(Failure.WORLD_UNAVAILABLE, "World edit was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof EditException editException) {
                throw editException;
            }
            throw new IllegalStateException("Could not prepare the world edit", exception.getCause());
        }
    }

    private PreparedEdit prepareOnMainThread(ReplaceRequest request, NormalizedRegion region)
            throws EditException {
        World world = this.plugin.getServer().getWorld(request.world());
        if (world == null) {
            throw new EditException(Failure.WORLD_NOT_FOUND, "World is not loaded: " + request.world());
        }
        if (region.min().y() < world.getMinHeight() || region.max().y() >= world.getMaxHeight()) {
            throw new EditException(
                    Failure.INVALID_REQUEST,
                    "Y bounds must be between " + world.getMinHeight() + " and " + (world.getMaxHeight() - 1));
        }
        requireLoadedChunks(world, region);

        BlockData source = parseBlockData(request.source(), "source");
        BlockData destination = parseBlockData(request.destination(), "destination");
        return new PreparedEdit(
                world.getName(),
                BukkitAdapter.adapt(world),
                BukkitAdapter.adapt(source).toBaseBlock(),
                BukkitAdapter.adapt(destination).toBaseBlock(),
                source.getAsString(),
                destination.getAsString());
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

    private void remember(String world, EditSession session) {
        Deque<EditSession> history = this.undoHistory.computeIfAbsent(world, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(session);
            while (history.size() > this.maxUndoEntries) {
                history.removeFirst();
            }
        }
    }

    private record PreparedEdit(
            String worldName,
            com.sk89q.worldedit.world.World world,
            BaseBlock source,
            BaseBlock destination,
            String sourceName,
            String destinationName) {}
}
