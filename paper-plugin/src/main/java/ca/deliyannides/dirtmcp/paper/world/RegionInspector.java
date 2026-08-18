package ca.deliyannides.dirtmcp.paper.world;

import java.io.Serial;
import java.util.List;
import java.util.Map;

public interface RegionInspector {
    InspectionResult inspect(InspectionRequest request) throws InspectionException;

    ExactInspectionResult inspectBlocks(ExactInspectionRequest request) throws InspectionException;

    ViewResult inspectView(ViewRequest request) throws InspectionException;

    record BlockPosition(int x, int y, int z) {}

    record InspectionRequest(String world, BlockPosition min, BlockPosition max) {}

    record Bounds(BlockPosition min, BlockPosition max) {}

    record Dimensions(long x, long y, long z) {}

    record InspectionResult(
            String world,
            Bounds bounds,
            Dimensions dimensions,
            long volume,
            Map<String, Long> blockStates) {}

    enum ExactInspectionMode {
        BLOCKS,
        RUNS
    }

    record ExactInspectionRequest(
            String world,
            BlockPosition min,
            BlockPosition max,
            List<String> include,
            List<String> exclude,
            boolean includeAir,
            int maxResults,
            ExactInspectionMode mode) {}

    sealed interface ExactInspectionResult permits BlockInspectionResult, RunInspectionResult {
        String world();

        Bounds bounds();

        long volume();

        long matchedBlocks();

        String mode();
    }

    record InspectedBlock(BlockPosition position, String state) {}

    record BlockRun(String state, BlockPosition from, BlockPosition to) {}

    record BlockInspectionResult(
            String world,
            Bounds bounds,
            long volume,
            long matchedBlocks,
            String mode,
            List<InspectedBlock> blocks) implements ExactInspectionResult {}

    record RunInspectionResult(
            String world,
            Bounds bounds,
            long volume,
            long matchedBlocks,
            String mode,
            List<BlockRun> runs) implements ExactInspectionResult {}

    enum ViewDirection {
        NORTH,
        EAST,
        SOUTH,
        WEST,
        UP,
        DOWN
    }

    record ViewRequest(
            String world,
            BlockPosition origin,
            ViewDirection direction,
            int horizontalRadius,
            int verticalRadius,
            int maxDistance,
            int maxResults) {}

    record AxisVector(int x, int y, int z) {}

    record ViewBasis(AxisVector forward, AxisVector horizontal, AxisVector vertical) {}

    record Viewport(int horizontalRadius, int verticalRadius, int maxDistance) {}

    record ViewOffset(int horizontal, int vertical, int distance) {}

    record ViewBlock(BlockPosition position, ViewOffset offset, String state) {}

    record ViewResult(
            String world,
            BlockPosition origin,
            String direction,
            ViewBasis basis,
            Viewport viewport,
            Bounds bounds,
            long scannedVolume,
            long visibleBlocks,
            List<ViewBlock> blocks) {}

    enum Failure {
        INVALID_REQUEST,
        REGION_TOO_LARGE,
        RESULT_TOO_LARGE,
        WORLD_NOT_FOUND,
        WORLD_UNAVAILABLE
    }

    final class InspectionException extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

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
