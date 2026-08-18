package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Bounds;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockInspectionResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockRun;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ExactInspectionMode;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ExactInspectionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ExactInspectionResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RunInspectionResult;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.NormalizedRegion;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.RegionTooLargeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperRegionInspector implements RegionInspector {
    private static final Comparator<InspectedBlock> BLOCK_ORDER =
            Comparator.comparingInt((InspectedBlock block) -> block.position().y())
                    .thenComparingInt(block -> block.position().z())
                    .thenComparingInt(block -> block.position().x());

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
        WorldCapture capture = capture(request.world(), region, List.of(), List.of());
        Map<String, Long> blockStates = countBlockStates(region, capture.snapshots());

        return new InspectionResult(
                capture.worldName(),
                new Bounds(region.min(), region.max()),
                region.dimensions(),
                region.volume(),
                blockStates);
    }

    @Override
    public ExactInspectionResult inspectBlocks(ExactInspectionRequest request) throws InspectionException {
        if (request.maxResults() < 1 || request.maxResults() > MAX_EXACT_RESULTS) {
            throw new InspectionException(
                    Failure.INVALID_REQUEST,
                    "maxResults must be between 1 and " + MAX_EXACT_RESULTS);
        }

        NormalizedRegion region = normalizeExact(request, this.maxRegionVolume);
        WorldCapture capture = capture(request.world(), region, request.include(), request.exclude());
        List<InspectedBlock> blocks = collectBlocks(region, capture, request);
        Bounds bounds = new Bounds(region.min(), region.max());
        if (request.mode() == ExactInspectionMode.BLOCKS) {
            return new BlockInspectionResult(
                    capture.worldName(),
                    bounds,
                    region.volume(),
                    blocks.size(),
                    "blocks",
                    List.copyOf(blocks));
        }

        List<BlockRun> runs = groupRuns(blocks, request.maxResults());
        return new RunInspectionResult(
                capture.worldName(),
                bounds,
                region.volume(),
                blocks.size(),
                "runs",
                runs);
    }

    static NormalizedRegion normalize(InspectionRequest request, long maxRegionVolume)
            throws InspectionException {
        return normalize(request.min(), request.max(), maxRegionVolume);
    }

    static NormalizedRegion normalizeExact(ExactInspectionRequest request, long maxRegionVolume)
            throws InspectionException {
        return normalize(request.min(), request.max(), Math.min(maxRegionVolume, MAX_EXACT_VOLUME));
    }

    private static NormalizedRegion normalize(
            BlockPosition min,
            BlockPosition max,
            long maxRegionVolume) throws InspectionException {
        try {
            return RegionGeometry.normalize(min, max, maxRegionVolume);
        } catch (RegionTooLargeException exception) {
            throw new InspectionException(Failure.REGION_TOO_LARGE, exception.getMessage(), exception);
        }
    }

    private WorldCapture capture(
            String worldName,
            NormalizedRegion region,
            List<String> include,
            List<String> exclude) throws InspectionException {
        Future<WorldCapture> capture = this.plugin.getServer()
                .getScheduler()
                .callSyncMethod(this.plugin, () -> captureOnMainThread(worldName, region, include, exclude));
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

    private WorldCapture captureOnMainThread(
            String worldName,
            NormalizedRegion region,
            List<String> include,
            List<String> exclude)
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

        List<BlockData> includePatterns = parsePatterns(include, "include");
        List<BlockData> excludePatterns = parsePatterns(exclude, "exclude");
        List<ChunkSnapshot> snapshots = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    throw new InspectionException(
                            Failure.WORLD_UNAVAILABLE,
                            "Region contains an unloaded chunk at " + chunkX + "," + chunkZ);
                }
                ChunkSnapshot snapshot = world.getChunkAt(chunkX, chunkZ, false)
                        .getChunkSnapshot(false, false, false, false);
                snapshots.add(snapshot);
            }
        }
        return new WorldCapture(
                world.getName(),
                List.copyOf(snapshots),
                includePatterns,
                excludePatterns);
    }

    private static List<BlockData> parsePatterns(List<String> inputs, String field)
            throws InspectionException {
        List<BlockData> patterns = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            try {
                patterns.add(Bukkit.createBlockData(input));
            } catch (IllegalArgumentException exception) {
                throw new InspectionException(
                        Failure.INVALID_REQUEST,
                        field + " contains an invalid block state: " + input,
                        exception);
            }
        }
        return List.copyOf(patterns);
    }

    private static Map<String, Long> countBlockStates(
            NormalizedRegion region, List<ChunkSnapshot> snapshots) {
        Map<String, Long> counts = new TreeMap<>();
        for (ChunkSnapshot snapshot : snapshots) {
            int chunkMinX = snapshot.getX() << 4;
            int chunkMinZ = snapshot.getZ() << 4;
            int minX = Math.max(region.min().x(), chunkMinX);
            int maxX = Math.min(region.max().x(), chunkMinX + 15);
            int minZ = Math.max(region.min().z(), chunkMinZ);
            int maxZ = Math.min(region.max().z(), chunkMinZ + 15);

            for (int y = region.min().y(); y <= region.max().y(); y++) {
                for (long z = minZ; z <= maxZ; z++) {
                    for (long x = minX; x <= maxX; x++) {
                        String blockState = snapshot
                                .getBlockData((int) x & 15, y, (int) z & 15)
                                .getAsString();
                        counts.merge(blockState, 1L, Long::sum);
                    }
                }
            }
        }
        return counts;
    }

    private static List<InspectedBlock> collectBlocks(
            NormalizedRegion region,
            WorldCapture capture,
            ExactInspectionRequest request) throws InspectionException {
        List<InspectedBlock> blocks = new ArrayList<>();
        for (ChunkSnapshot snapshot : capture.snapshots()) {
            int chunkMinX = snapshot.getX() << 4;
            int chunkMinZ = snapshot.getZ() << 4;
            int minX = Math.max(region.min().x(), chunkMinX);
            int maxX = Math.min(region.max().x(), chunkMinX + 15);
            int minZ = Math.max(region.min().z(), chunkMinZ);
            int maxZ = Math.min(region.max().z(), chunkMinZ + 15);

            for (int y = region.min().y(); y <= region.max().y(); y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        BlockData blockData = snapshot.getBlockData(x & 15, y, z & 15);
                        if (!matches(blockData, capture, request.includeAir())) {
                            continue;
                        }
                        blocks.add(new InspectedBlock(
                                new BlockPosition(x, y, z),
                                blockData.getAsString()));
                        if (request.mode() == ExactInspectionMode.BLOCKS
                                && blocks.size() > request.maxResults()) {
                            throw resultTooLarge(request.maxResults());
                        }
                    }
                }
            }
        }
        blocks.sort(BLOCK_ORDER);
        return blocks;
    }

    private static boolean matches(BlockData candidate, WorldCapture capture, boolean includeAir) {
        if (!includeAir && candidate.getMaterial().isAir()) {
            return false;
        }
        if (!capture.includePatterns().isEmpty()
                && capture.includePatterns().stream().noneMatch(candidate::matches)) {
            return false;
        }
        return capture.excludePatterns().stream().noneMatch(candidate::matches);
    }

    static List<BlockRun> groupRuns(List<InspectedBlock> blocks, int maxResults)
            throws InspectionException {
        List<InspectedBlock> orderedBlocks = new ArrayList<>(blocks);
        orderedBlocks.sort(BLOCK_ORDER);
        Map<BlockPosition, String> remaining = new HashMap<>();
        for (InspectedBlock block : orderedBlocks) {
            remaining.put(block.position(), block.state());
        }

        List<BlockRun> runs = new ArrayList<>();
        for (InspectedBlock block : orderedBlocks) {
            String state = remaining.get(block.position());
            if (state == null) {
                continue;
            }

            Axis axis = Axis.X;
            int length = 0;
            for (Axis candidate : Axis.values()) {
                int candidateLength = runLength(block.position(), state, candidate, remaining);
                if (candidateLength > length) {
                    axis = candidate;
                    length = candidateLength;
                }
            }

            BlockPosition to = advance(block.position(), axis, length - 1);
            runs.add(new BlockRun(state, block.position(), to));
            if (runs.size() > maxResults) {
                throw resultTooLarge(maxResults);
            }
            for (int offset = 0; offset < length; offset++) {
                remaining.remove(advance(block.position(), axis, offset));
            }
        }
        return List.copyOf(runs);
    }

    private static int runLength(
            BlockPosition from,
            String state,
            Axis axis,
            Map<BlockPosition, String> remaining) {
        int length = 1;
        while (state.equals(remaining.get(advance(from, axis, length)))) {
            length++;
        }
        return length;
    }

    private static BlockPosition advance(BlockPosition position, Axis axis, int distance) {
        long x = position.x() + (long) axis.x * distance;
        long y = position.y() + (long) axis.y * distance;
        long z = position.z() + (long) axis.z * distance;
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            return null;
        }
        return new BlockPosition((int) x, (int) y, (int) z);
    }

    private static InspectionException resultTooLarge(int maxResults) {
        return new InspectionException(
                Failure.RESULT_TOO_LARGE,
                "Inspection result exceeds maxResults of " + maxResults + " entries");
    }

    private enum Axis {
        X(1, 0, 0),
        Y(0, 1, 0),
        Z(0, 0, 1);

        private final int x;
        private final int y;
        private final int z;

        Axis(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private record WorldCapture(
            String worldName,
            List<ChunkSnapshot> snapshots,
            List<BlockData> includePatterns,
            List<BlockData> excludePatterns) {}
}
