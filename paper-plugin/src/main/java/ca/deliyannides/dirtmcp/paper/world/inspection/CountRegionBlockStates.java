package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockDimensions;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@FunctionalInterface
public interface CountRegionBlockStates {
    Result countRegionBlockStates(Request request) throws OperationException;

    record Request(String world, BlockPosition min, BlockPosition max) {
        public Request {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
        }
    }

    record Result(
            String world,
            BlockBounds bounds,
            BlockDimensions dimensions,
            long volume,
            Map<String, Long> blockStateCounts) {
        public Result {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(dimensions, "dimensions");
            if (volume < 1) {
                throw new IllegalArgumentException("Region volume must be positive");
            }
            blockStateCounts =
                    Collections.unmodifiableMap(
                            new TreeMap<>(Objects.requireNonNull(blockStateCounts, "counts")));
        }
    }
}
