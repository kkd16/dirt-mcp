package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface GetRegionBlocks {
    Result getRegionBlocks(Request request) throws OperationException;

    enum Format {
        BLOCKS,
        RUNS
    }

    record Request(
            String world,
            BlockPosition min,
            BlockPosition max,
            List<String> includeBlockStatePatterns,
            List<String> excludeBlockStatePatterns,
            boolean includeAir,
            int maxResults,
            Format format) {
        public Request {
            includeBlockStatePatterns = immutableCopy(includeBlockStatePatterns);
            excludeBlockStatePatterns = immutableCopy(excludeBlockStatePatterns);
        }
    }

    sealed interface Result permits BlockListResult, BlockRunsResult {
        String world();

        BlockBounds bounds();

        long volume();

        long matchedBlockCount();

        String format();
    }

    record InspectedBlock(BlockPosition position, String blockState) {
        public InspectedBlock {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(blockState, "blockState");
        }
    }

    record BlockRun(String blockState, BlockPosition from, BlockPosition to) {
        public BlockRun {
            Objects.requireNonNull(blockState, "blockState");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    record BlockListResult(
            String world,
            BlockBounds bounds,
            long volume,
            long matchedBlockCount,
            String format,
            List<InspectedBlock> blocks)
            implements Result {
        public BlockListResult {
            requireResultFields(world, bounds, volume, matchedBlockCount, format);
            blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        }
    }

    record BlockRunsResult(
            String world,
            BlockBounds bounds,
            long volume,
            long matchedBlockCount,
            String format,
            List<BlockRun> runs)
            implements Result {
        public BlockRunsResult {
            requireResultFields(world, bounds, volume, matchedBlockCount, format);
            runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
        }
    }

    private static void requireResultFields(
            String world, BlockBounds bounds, long volume, long matchedBlockCount, String format) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(format, "format");
        if (volume < 1) {
            throw new IllegalArgumentException("Region volume must be positive");
        }
        if (matchedBlockCount < 0) {
            throw new IllegalArgumentException("Matched block count must be non-negative");
        }
    }

    private static List<String> immutableCopy(List<String> values) {
        // List.copyOf would reject null entries before operation-layer validation can describe
        // them.
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
