package ca.deliyannides.dirtmcp.paper.world;

import java.io.Serial;
import java.util.List;
import java.util.Map;

public interface RegionInspector {
    BlockStateCountResult countRegionBlockStates(BlockStateCountRequest request)
            throws InspectionException;

    RegionBlocksResult getRegionBlocks(RegionBlocksRequest request) throws InspectionException;

    OrthographicViewResult scanOrthographicView(OrthographicViewRequest request)
            throws InspectionException;

    record BlockPosition(int x, int y, int z) {}

    record BlockStateCountRequest(String world, BlockPosition min, BlockPosition max) {}

    record Bounds(BlockPosition min, BlockPosition max) {}

    record Dimensions(long x, long y, long z) {}

    record BlockStateCountResult(
            String world,
            Bounds bounds,
            Dimensions dimensions,
            long volume,
            Map<String, Long> blockStateCounts) {}

    enum RegionBlocksFormat {
        BLOCKS,
        RUNS
    }

    record RegionBlocksRequest(
            String world,
            BlockPosition min,
            BlockPosition max,
            List<String> includeBlockStatePatterns,
            List<String> excludeBlockStatePatterns,
            boolean includeAir,
            int maxResults,
            RegionBlocksFormat format) {}

    sealed interface RegionBlocksResult permits RegionBlockListResult, RegionBlockRunsResult {
        String world();

        Bounds bounds();

        long volume();

        long matchedBlockCount();

        String format();
    }

    record InspectedBlock(BlockPosition position, String blockState) {}

    record BlockRun(String blockState, BlockPosition from, BlockPosition to) {}

    record RegionBlockListResult(
            String world,
            Bounds bounds,
            long volume,
            long matchedBlockCount,
            String format,
            List<InspectedBlock> blocks)
            implements RegionBlocksResult {}

    record RegionBlockRunsResult(
            String world,
            Bounds bounds,
            long volume,
            long matchedBlockCount,
            String format,
            List<BlockRun> runs)
            implements RegionBlocksResult {}

    enum OrthographicViewDirection {
        NORTH,
        EAST,
        SOUTH,
        WEST,
        UP,
        DOWN
    }

    record OrthographicViewRequest(
            String world,
            BlockPosition origin,
            OrthographicViewDirection direction,
            int horizontalRadius,
            int verticalRadius,
            int maxDistance,
            int maxResults) {}

    record AxisVector(int x, int y, int z) {}

    record ViewBasis(AxisVector forward, AxisVector horizontal, AxisVector vertical) {}

    record Viewport(int horizontalRadius, int verticalRadius, int maxDistance) {}

    record ViewOffset(int horizontal, int vertical, int distance) {}

    record ViewBlock(BlockPosition position, ViewOffset offset, String blockState) {}

    record OrthographicViewResult(
            String world,
            BlockPosition origin,
            String direction,
            String format,
            ViewBasis basis,
            Viewport viewport,
            Bounds bounds,
            long scannedVolume,
            long visibleBlockCount,
            List<ViewBlock> blocks) {}

    enum Failure {
        INVALID_REQUEST,
        REGION_TOO_LARGE,
        RESULT_TOO_LARGE,
        WORLD_NOT_FOUND,
        WORLD_UNAVAILABLE
    }

    final class InspectionException extends Exception {
        @Serial private static final long serialVersionUID = 1L;

        private final Failure failure;

        public InspectionException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public InspectionException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        public Failure failure() {
            return this.failure;
        }
    }
}
