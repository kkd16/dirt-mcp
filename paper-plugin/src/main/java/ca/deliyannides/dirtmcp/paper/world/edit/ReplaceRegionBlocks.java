package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface ReplaceRegionBlocks {
    Result replaceRegionBlocks(Request request, UUID callId) throws OperationException;

    record Request(
            String world,
            BlockPosition min,
            BlockPosition max,
            List<String> sourceBlockStatePatterns,
            List<DestinationPaletteEntry> destinationPalette,
            int seed,
            boolean dryRun) {
        public Request {
            sourceBlockStatePatterns = immutableCopy(sourceBlockStatePatterns);
            destinationPalette = immutableCopy(destinationPalette);
        }
    }

    record Result(
            String world,
            BlockBounds bounds,
            List<String> sourceBlockStatePatterns,
            List<DestinationPaletteEntry> destinationPalette,
            int seed,
            EditOutcome outcome,
            long matchedBlockCount,
            long changedBlockCount,
            EditRecord edit) {
        public Result {
            sourceBlockStatePatterns = List.copyOf(sourceBlockStatePatterns);
            destinationPalette = List.copyOf(destinationPalette);
            if (matchedBlockCount < 0 || changedBlockCount > matchedBlockCount) {
                throw new IllegalArgumentException(
                        "changedBlockCount must not exceed matchedBlockCount");
            }
            EditRecord.validateResult(
                    outcome,
                    edit,
                    EditOperation.REPLACE_REGION_BLOCKS,
                    world,
                    bounds,
                    changedBlockCount);
        }
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        // List.copyOf would reject null entries before operation-layer validation can describe
        // them.
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
