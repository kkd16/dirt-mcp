package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.List;
import java.util.Objects;
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
            boolean dryRun,
            String label,
            Integer maxChangedBlocks) {
        public Request {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            sourceBlockStatePatterns = List.copyOf(sourceBlockStatePatterns);
            destinationPalette = List.copyOf(destinationPalette);
            Objects.requireNonNull(label, "label");
        }
    }

    record Result(
            String world,
            BlockBounds bounds,
            int seed,
            EditOutcome outcome,
            long matchedBlockCount,
            long changedBlockCount,
            EditRecord edit) {}
}
