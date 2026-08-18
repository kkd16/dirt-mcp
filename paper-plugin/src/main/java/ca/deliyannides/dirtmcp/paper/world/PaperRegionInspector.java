package ca.deliyannides.dirtmcp.paper.world;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector.AxisVector;
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
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewBasis;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewBlock;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewDirection;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewOffset;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Viewport;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.NormalizedRegion;
import ca.deliyannides.dirtmcp.paper.world.RegionGeometry.RegionTooLargeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;
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
    private final long maxExactInspectionVolume;
    private final int maxExactResults;
    private final long maxViewVolume;
    private final int maxViewResults;

    public PaperRegionInspector(
            JavaPlugin plugin,
            long maxRegionVolume,
            long maxExactInspectionVolume,
            int maxExactResults,
            long maxViewVolume,
            int maxViewResults) {
        if (maxRegionVolume < 1
                || maxExactInspectionVolume < 1
                || maxExactResults < 1
                || maxViewVolume < 1
                || maxViewResults < 1) {
            throw new IllegalArgumentException("Inspection limits must be positive");
        }
        this.plugin = plugin;
        this.maxRegionVolume = maxRegionVolume;
        this.maxExactInspectionVolume = maxExactInspectionVolume;
        this.maxExactResults = maxExactResults;
        this.maxViewVolume = maxViewVolume;
        this.maxViewResults = maxViewResults;
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
        if (request.maxResults() < 1 || request.maxResults() > this.maxExactResults) {
            throw new InspectionException(
                    Failure.INVALID_REQUEST,
                    "maxResults must be between 1 and " + this.maxExactResults);
        }

        NormalizedRegion region = normalizeExact(
                request, this.maxRegionVolume, this.maxExactInspectionVolume);
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

        List<BlockRun> runs = groupSortedRuns(blocks, request.maxResults());
        return new RunInspectionResult(
                capture.worldName(),
                bounds,
                region.volume(),
                blocks.size(),
                "runs",
                runs);
    }

    @Override
    public ViewResult inspectView(ViewRequest request) throws InspectionException {
        ViewGeometry geometry = normalizeView(
                request,
                this.maxRegionVolume,
                this.maxViewVolume,
                this.maxViewResults);
        WorldCapture capture = capture(request.world(), geometry.region(), List.of(), List.of());
        List<ViewBlock> blocks = collectViewBlocks(
                request,
                geometry,
                position -> visibleStateAt(capture, position));

        return new ViewResult(
                capture.worldName(),
                request.origin(),
                request.direction().name().toLowerCase(Locale.ROOT),
                geometry.basis(),
                new Viewport(
                        request.horizontalRadius(),
                        request.verticalRadius(),
                        request.maxDistance()),
                new Bounds(geometry.region().min(), geometry.region().max()),
                geometry.scannedVolume(),
                blocks.size(),
                blocks);
    }

    static NormalizedRegion normalize(InspectionRequest request, long maxRegionVolume)
            throws InspectionException {
        return normalize(request.min(), request.max(), maxRegionVolume);
    }

    static NormalizedRegion normalizeExact(
            ExactInspectionRequest request,
            long maxRegionVolume,
            long maxExactInspectionVolume)
            throws InspectionException {
        return normalize(
                request.min(),
                request.max(),
                Math.min(maxRegionVolume, maxExactInspectionVolume));
    }

    static ViewGeometry normalizeView(
            ViewRequest request,
            long maxRegionVolume,
            long maxViewVolume,
            int maxViewResults)
            throws InspectionException {
        if (request.direction() == null) {
            throw new InspectionException(Failure.INVALID_REQUEST, "direction is required");
        }
        if (request.horizontalRadius() < 0 || request.verticalRadius() < 0) {
            throw new InspectionException(
                    Failure.INVALID_REQUEST,
                    "horizontalRadius and verticalRadius must be non-negative");
        }
        if (request.maxDistance() < 1) {
            throw new InspectionException(Failure.INVALID_REQUEST, "maxDistance must be positive");
        }
        if (request.maxResults() < 1 || request.maxResults() > maxViewResults) {
            throw new InspectionException(
                    Failure.INVALID_REQUEST,
                    "maxResults must be between 1 and " + maxViewResults);
        }

        long maximum = Math.min(maxRegionVolume, maxViewVolume);
        long horizontalSize = 2L * request.horizontalRadius() + 1;
        long verticalSize = 2L * request.verticalRadius() + 1;
        if (horizontalSize > maximum
                || verticalSize > maximum / horizontalSize
                || request.maxDistance() > maximum / (horizontalSize * verticalSize)) {
            throw new InspectionException(
                    Failure.REGION_TOO_LARGE,
                    "View exceeds the maximum scan volume of " + maximum + " blocks");
        }
        long scannedVolume = horizontalSize * verticalSize * request.maxDistance();

        ViewBasis basis = viewBasis(request.direction());
        BlockPosition firstCorner = viewPosition(
                request.origin(),
                basis,
                -request.horizontalRadius(),
                -request.verticalRadius(),
                1);
        BlockPosition min = firstCorner;
        BlockPosition max = firstCorner;
        int[] horizontalOffsets = {-request.horizontalRadius(), request.horizontalRadius()};
        int[] verticalOffsets = {-request.verticalRadius(), request.verticalRadius()};
        int[] distances = {1, request.maxDistance()};
        for (int horizontal : horizontalOffsets) {
            for (int vertical : verticalOffsets) {
                for (int distance : distances) {
                    BlockPosition corner = viewPosition(
                            request.origin(), basis, horizontal, vertical, distance);
                    min = new BlockPosition(
                            Math.min(min.x(), corner.x()),
                            Math.min(min.y(), corner.y()),
                            Math.min(min.z(), corner.z()));
                    max = new BlockPosition(
                            Math.max(max.x(), corner.x()),
                            Math.max(max.y(), corner.y()),
                            Math.max(max.z(), corner.z()));
                }
            }
        }

        NormalizedRegion region = normalize(min, max, maximum);
        if (region.volume() != scannedVolume) {
            throw new IllegalStateException("View bounds do not match its scan volume");
        }
        return new ViewGeometry(basis, region, scannedVolume);
    }

    static List<ViewBlock> collectViewBlocks(
            ViewRequest request,
            ViewGeometry geometry,
            Function<BlockPosition, String> blockStateAt) throws InspectionException {
        List<ViewBlock> blocks = new ArrayList<>();
        for (int vertical = request.verticalRadius(); vertical >= -request.verticalRadius(); vertical--) {
            for (int horizontal = -request.horizontalRadius();
                    horizontal <= request.horizontalRadius();
                    horizontal++) {
                for (int distance = 1; distance <= request.maxDistance(); distance++) {
                    BlockPosition position = viewPosition(
                            request.origin(), geometry.basis(), horizontal, vertical, distance);
                    String state = blockStateAt.apply(position);
                    if (state == null) {
                        continue;
                    }
                    blocks.add(new ViewBlock(
                            position,
                            new ViewOffset(horizontal, vertical, distance),
                            state));
                    if (blocks.size() > request.maxResults()) {
                        throw new InspectionException(
                                Failure.RESULT_TOO_LARGE,
                                "View result exceeds maxResults of "
                                        + request.maxResults()
                                        + " visible blocks");
                    }
                    break;
                }
            }
        }
        return List.copyOf(blocks);
    }

    private static ViewBasis viewBasis(ViewDirection direction) {
        AxisVector worldUp = new AxisVector(0, 1, 0);
        return switch (direction) {
            case NORTH -> new ViewBasis(
                    new AxisVector(0, 0, -1), new AxisVector(1, 0, 0), worldUp);
            case EAST -> new ViewBasis(
                    new AxisVector(1, 0, 0), new AxisVector(0, 0, 1), worldUp);
            case SOUTH -> new ViewBasis(
                    new AxisVector(0, 0, 1), new AxisVector(-1, 0, 0), worldUp);
            case WEST -> new ViewBasis(
                    new AxisVector(-1, 0, 0), new AxisVector(0, 0, -1), worldUp);
            case UP -> new ViewBasis(
                    new AxisVector(0, 1, 0), new AxisVector(1, 0, 0), new AxisVector(0, 0, -1));
            case DOWN -> new ViewBasis(
                    new AxisVector(0, -1, 0), new AxisVector(1, 0, 0), new AxisVector(0, 0, -1));
        };
    }

    private static BlockPosition viewPosition(
            BlockPosition origin,
            ViewBasis basis,
            int horizontal,
            int vertical,
            int distance) throws InspectionException {
        long x = origin.x()
                + (long) basis.horizontal().x() * horizontal
                + (long) basis.vertical().x() * vertical
                + (long) basis.forward().x() * distance;
        long y = origin.y()
                + (long) basis.horizontal().y() * horizontal
                + (long) basis.vertical().y() * vertical
                + (long) basis.forward().y() * distance;
        long z = origin.z()
                + (long) basis.horizontal().z() * horizontal
                + (long) basis.vertical().z() * vertical
                + (long) basis.forward().z() * distance;
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
            throw new InspectionException(
                    Failure.INVALID_REQUEST,
                    "View extends beyond signed 32-bit block coordinates");
        }
        return new BlockPosition((int) x, (int) y, (int) z);
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
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    throw new InspectionException(
                            Failure.WORLD_UNAVAILABLE,
                            "Region contains an unloaded chunk at " + chunkX + "," + chunkZ);
                }
            }
        }

        List<ChunkSnapshot> snapshots = new ArrayList<>();
        Map<Long, ChunkSnapshot> snapshotsByChunk = new HashMap<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkSnapshot snapshot = world.getChunkAt(chunkX, chunkZ, false)
                        .getChunkSnapshot(false, false, false, false);
                snapshots.add(snapshot);
                snapshotsByChunk.put(chunkKey(chunkX, chunkZ), snapshot);
            }
        }
        return new WorldCapture(
                world.getName(),
                List.copyOf(snapshots),
                Map.copyOf(snapshotsByChunk),
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

    private static String visibleStateAt(WorldCapture capture, BlockPosition position) {
        ChunkSnapshot snapshot = capture.snapshotsByChunk().get(chunkKey(position.x() >> 4, position.z() >> 4));
        if (snapshot == null) {
            throw new IllegalStateException("Captured view is missing a required chunk snapshot");
        }
        BlockData blockData = snapshot.getBlockData(position.x() & 15, position.y(), position.z() & 15);
        return blockData.getMaterial().isAir() ? null : blockData.getAsString();
    }

    static List<BlockRun> groupSortedRuns(List<InspectedBlock> blocks, int maxResults)
            throws InspectionException {
        Map<BlockPosition, String> remaining = new HashMap<>();
        for (InspectedBlock block : blocks) {
            remaining.put(block.position(), block.state());
        }

        List<BlockRun> runs = new ArrayList<>();
        for (InspectedBlock block : blocks) {
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

    record ViewGeometry(ViewBasis basis, NormalizedRegion region, long scannedVolume) {}

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffff_ffffL);
    }

    private record WorldCapture(
            String worldName,
            List<ChunkSnapshot> snapshots,
            Map<Long, ChunkSnapshot> snapshotsByChunk,
            List<BlockData> includePatterns,
            List<BlockData> excludePatterns) {}
}
