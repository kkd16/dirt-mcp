package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Cuboid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

public final class PaperRegionSnapshotSource implements RegionSnapshotSource {
    private final Server server;
    private final MainThread mainThread;
    private final Predicate<BlockData> airBlocks;

    public PaperRegionSnapshotSource(Server server, MainThread mainThread) {
        this(server, mainThread, blockData -> blockData.getMaterial().isAir());
    }

    PaperRegionSnapshotSource(
            Server server, MainThread mainThread, Predicate<BlockData> airBlocks) {
        this.server = Objects.requireNonNull(server, "server");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.airBlocks = Objects.requireNonNull(airBlocks, "airBlocks");
    }

    @Override
    public CapturedRegion capture(
            String world,
            Cuboid region,
            List<String> includeBlockStatePatterns,
            List<String> excludeBlockStatePatterns)
            throws OperationException {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(includeBlockStatePatterns, "includeBlockStatePatterns");
        Objects.requireNonNull(excludeBlockStatePatterns, "excludeBlockStatePatterns");
        try {
            return this.mainThread.call(
                    () ->
                            captureOnMainThread(
                                    world,
                                    region,
                                    includeBlockStatePatterns,
                                    excludeBlockStatePatterns));
        } catch (PaperMainThreadException exception) {
            if (exception.getCause() instanceof OperationException operationException) {
                throw operationException;
            }
            String message =
                    Thread.currentThread().isInterrupted()
                            ? "World inspection was interrupted"
                            : "World inspection is unavailable";
            ErrorDetails.WorldUnavailable details =
                    Thread.currentThread().isInterrupted()
                            ? new ErrorDetails.WorldUnavailable.Interrupted()
                            : new ErrorDetails.WorldUnavailable.PaperUnavailable();
            throw new OperationException(
                    OperationFailure.WORLD_UNAVAILABLE, message, details, exception);
        }
    }

    private CapturedRegion captureOnMainThread(
            String worldName,
            Cuboid region,
            List<String> includeBlockStatePatterns,
            List<String> excludeBlockStatePatterns)
            throws OperationException {
        World world = this.server.getWorld(worldName);
        if (world == null || !worldName.equals(world.getName())) {
            throw new OperationException(
                    OperationFailure.WORLD_NOT_FOUND,
                    "World is not loaded with the exact name: " + worldName,
                    new ErrorDetails.WorldNotFound(worldName));
        }
        if (region.min().y() < world.getMinHeight() || region.max().y() >= world.getMaxHeight()) {
            boolean minimumInvalid = region.min().y() < world.getMinHeight();
            String target = minimumInvalid ? "bounds.min.y" : "bounds.max.y";
            int value = minimumInvalid ? region.min().y() : region.max().y();
            throw new OperationException(
                    OperationFailure.INVALID_REQUEST,
                    "Y bounds must be between "
                            + world.getMinHeight()
                            + " and "
                            + (world.getMaxHeight() - 1),
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            target, value, world.getMinHeight(), world.getMaxHeight() - 1));
        }

        List<BlockData> includePatterns =
                parsePatterns(includeBlockStatePatterns, "includeBlockStatePatterns");
        List<BlockData> excludePatterns =
                parsePatterns(excludeBlockStatePatterns, "excludeBlockStatePatterns");
        int minChunkX = region.min().x() >> 4;
        int maxChunkX = region.max().x() >> 4;
        int minChunkZ = region.min().z() >> 4;
        int maxChunkZ = region.max().z() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    throw new OperationException(
                            OperationFailure.WORLD_UNAVAILABLE,
                            "Region contains an unloaded chunk at " + chunkX + "," + chunkZ,
                            new ErrorDetails.WorldUnavailable.ChunkUnloaded(
                                    worldName, new ErrorDetails.Chunk(chunkX, chunkZ)));
                }
            }
        }

        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkSnapshot snapshot =
                        world.getChunkAt(chunkX, chunkZ, false)
                                .getChunkSnapshot(false, false, false, false);
                snapshots.put(chunkKey(chunkX, chunkZ), snapshot);
            }
        }
        return new PaperCapturedRegion(
                world.getName(),
                Map.copyOf(snapshots),
                includePatterns,
                excludePatterns,
                this.airBlocks);
    }

    private List<BlockData> parsePatterns(List<String> inputs, String field)
            throws OperationException {
        List<BlockData> patterns = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            String input = inputs.get(index);
            try {
                patterns.add(this.server.createBlockData(input));
            } catch (IllegalArgumentException exception) {
                throw new OperationException(
                        OperationFailure.INVALID_REQUEST,
                        field + " contains an invalid block state: " + input,
                        new ErrorDetails.InvalidRequest.InvalidValue(field + "[" + index + "]"),
                        exception);
            }
        }
        return List.copyOf(patterns);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffff_ffffL);
    }

    private record PaperCapturedRegion(
            String worldName,
            Map<Long, ChunkSnapshot> snapshots,
            List<BlockData> includePatterns,
            List<BlockData> excludePatterns,
            Predicate<BlockData> airBlocks)
            implements CapturedRegion {
        @Override
        public BlockSample sample(BlockPosition position) {
            ChunkSnapshot snapshot =
                    this.snapshots.get(chunkKey(position.x() >> 4, position.z() >> 4));
            if (snapshot == null) {
                throw new IllegalStateException("Captured region is missing a required chunk");
            }
            BlockData candidate =
                    snapshot.getBlockData(position.x() & 15, position.y(), position.z() & 15);
            boolean included =
                    (this.includePatterns.isEmpty()
                                    || this.includePatterns.stream().anyMatch(candidate::matches))
                            && this.excludePatterns.stream().noneMatch(candidate::matches);
            return new BlockSample(
                    candidate.getAsString(), this.airBlocks.test(candidate), included);
        }
    }
}
