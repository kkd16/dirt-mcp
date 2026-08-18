package ca.deliyannides.dirtmcp.paper.world;

import java.io.Serial;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public interface RegionInspector {
    InspectionResult inspect(InspectionRequest request) throws InspectionException;

    record BlockPosition(int x, int y, int z) {}

    record InspectionRequest(String world, BlockPosition min, BlockPosition max) {}

    record Bounds(BlockPosition min, BlockPosition max) {}

    record Dimensions(long x, long y, long z) {}

    record InspectionResult(
            String world,
            Bounds bounds,
            Dimensions dimensions,
            long volume,
            Map<String, Long> blockStates) {
        public InspectionResult {
            blockStates = Collections.unmodifiableMap(new LinkedHashMap<>(blockStates));
        }
    }

    enum Failure {
        INVALID_REQUEST,
        REGION_TOO_LARGE,
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
