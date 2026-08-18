package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Bounds;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Dimensions;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperRegionInspector implements RegionInspector {
    private final JavaPlugin plugin;
    private final long maxRegionVolume;

    public PaperRegionInspector(JavaPlugin plugin, long maxRegionVolume) {
        if (maxRegionVolume < 1) {
            throw new IllegalArgumentException("Maximum region volume must be positive");
        }
        this.plugin = plugin;
        this.maxRegionVolume = maxRegionVolume;
    }

    @Override
    public InspectionResult inspect(InspectionRequest request) throws InspectionException {
        NormalizedRegion region = normalize(request, this.maxRegionVolume);
        WorldCapture capture = capture(request.world(), region);
        Map<String, Long> blockStates = countBlockStates(region, capture.snapshots());

        return new InspectionResult(
                capture.worldName(),
                new Bounds(region.min(), region.max()),
                region.dimensions(),
                region.volume(),
                blockStates);
    }

    static NormalizedRegion normalize(InspectionRequest request, long maxRegionVolume)
            throws InspectionException {
        BlockPosition min = new BlockPosition(
                Math.min(request.min().x(), request.max().x()),
                Math.min(request.min().y(), request.max().y()),
                Math.min(request.min().z(), request.max().z()));
        BlockPosition max = new BlockPosition(
                Math.max(request.min().x(), request.max().x()),
                Math.max(request.min().y(), request.max().y()),
                Math.max(request.min().z(), request.max().z()));

        long sizeX = (long) max.x() - min.x() + 1;
        long sizeY = (long) max.y() - min.y() + 1;
        long sizeZ = (long) max.z() - min.z() + 1;
        long volume = cappedProduct(sizeX, sizeY, sizeZ, maxRegionVolume);

        return new NormalizedRegion(min, max, new Dimensions(sizeX, sizeY, sizeZ), volume);
    }

    private WorldCapture capture(String worldName, NormalizedRegion region) throws InspectionException {
        Future<WorldCapture> capture = this.plugin.getServer()
                .getScheduler()
                .callSyncMethod(this.plugin, () -> captureOnMainThread(worldName, region));
        try {
            return capture.get();
        } catch (InterruptedException exception) {
            capture.cancel(false);
            Thread.currentThread().interrupt();
            throw new InspectionException(
                    Failure.WORLD_UNAVAILABLE, "World inspection was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof InspectionException inspectionException) {
                throw inspectionException;
            }
            throw new IllegalStateException("Could not capture region snapshots", exception.getCause());
        }
    }

    private WorldCapture captureOnMainThread(String worldName, NormalizedRegion region)
            throws InspectionException {
        World world = this.plugin.getServer().getWorld(worldName);
        if (world == null) {
            throw new InspectionException(Failure.WORLD_NOT_FOUND, "World is not loaded: " + worldName);
        }
        if (region.min().y() < world.getMinHeight() || region.max().y() >= world.getMaxHeight()) {
            throw new InspectionException(
                    Failure.INVALID_REQUEST,
                    "Y bounds must be between " + world.getMinHeight() + " and " + (world.getMaxHeight() - 1));
        }

        int minChunkX = region.min().x() >> 4;
        int maxChunkX = region.max().x() >> 4;
        int minChunkZ = region.min().z() >> 4;
        int maxChunkZ = region.max().z() >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    throw new InspectionException(
                            Failure.WORLD_UNAVAILABLE,
                            "Region contains an unloaded chunk at " + chunkX + "," + chunkZ);
                }
            }
        }

        List<CapturedChunk> snapshots = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkSnapshot snapshot = world.getChunkAt(chunkX, chunkZ, false)
                        .getChunkSnapshot(false, false, false, false);
                snapshots.add(new CapturedChunk(chunkX, chunkZ, snapshot));
            }
        }
        return new WorldCapture(world.getName(), snapshots);
    }

    private static Map<String, Long> countBlockStates(
            NormalizedRegion region, List<CapturedChunk> snapshots) {
        Map<String, Long> counts = new TreeMap<>();
        for (CapturedChunk captured : snapshots) {
            int chunkMinX = captured.x() << 4;
            int chunkMinZ = captured.z() << 4;
            int minX = Math.max(region.min().x(), chunkMinX);
            int maxX = Math.min(region.max().x(), chunkMinX + 15);
            int minZ = Math.max(region.min().z(), chunkMinZ);
            int maxZ = Math.min(region.max().z(), chunkMinZ + 15);

            for (int y = region.min().y(); y <= region.max().y(); y++) {
                for (long z = minZ; z <= maxZ; z++) {
                    for (long x = minX; x <= maxX; x++) {
                        String blockState = captured.snapshot()
                                .getBlockData((int) x & 15, y, (int) z & 15)
                                .getAsString();
                        counts.merge(blockState, 1L, Long::sum);
                    }
                }
            }
        }
        return counts;
    }

    private static long cappedProduct(long sizeX, long sizeY, long sizeZ, long maximum)
            throws InspectionException {
        if (sizeX > maximum || sizeY > maximum / sizeX || sizeZ > maximum / (sizeX * sizeY)) {
            throw new InspectionException(
                    Failure.REGION_TOO_LARGE, "Region exceeds the maximum volume of " + maximum + " blocks");
        }
        return sizeX * sizeY * sizeZ;
    }

    record NormalizedRegion(BlockPosition min, BlockPosition max, Dimensions dimensions, long volume) {}

    private record CapturedChunk(int x, int z, ChunkSnapshot snapshot) {}

    private record WorldCapture(String worldName, List<CapturedChunk> snapshots) {}
}
