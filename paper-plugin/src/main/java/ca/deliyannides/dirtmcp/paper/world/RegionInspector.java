package ca.deliyannides.dirtmcp.paper.world;

import java.io.Serial;
import java.util.List;
import java.util.Map;

public interface RegionInspector {
    int MAX_EXACT_VOLUME = 32_768;
    int MAX_EXACT_RESULTS = 10_000;

    InspectionResult inspect(InspectionRequest request) throws InspectionException;

    ExactInspectionResult inspectBlocks(ExactInspectionRequest request) throws InspectionException;

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
