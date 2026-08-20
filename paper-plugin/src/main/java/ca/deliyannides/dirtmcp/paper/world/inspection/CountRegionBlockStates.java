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

    record Request(String world, BlockPosition min, BlockPosition max) {}

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
            Objects.requireNonNull(blockStateCounts, "counts");
            long counted = 0;
            for (Map.Entry<String, Long> entry : blockStateCounts.entrySet()) {
                if (entry.getKey() == null
                        || entry.getKey().isBlank()
                        || entry.getValue() == null
                        || entry.getValue() < 0) {
                    throw new IllegalArgumentException(
                            "Block-state counts must use non-empty states and non-negative values");
                }
                counted = Math.addExact(counted, entry.getValue());
            }
            if (counted != volume) {
                throw new IllegalArgumentException("Block-state counts must total region volume");
            }
            TreeMap<String, Long> sorted = new TreeMap<>(blockStateCounts);
            blockStateCounts = Collections.unmodifiableMap(sorted);
        }
    }
}
