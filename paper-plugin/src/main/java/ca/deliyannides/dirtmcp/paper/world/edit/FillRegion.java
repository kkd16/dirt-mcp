package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface FillRegion {
    Result fillRegion(Request request, UUID callId) throws OperationException;

    record Request(
            String world,
            BlockPosition min,
            BlockPosition max,
            List<DestinationPaletteEntry> destinationPalette,
            int seed,
            boolean dryRun) {
        public Request {
            // Preserve malformed null entries for operation-layer INVALID_REQUEST reporting.
            destinationPalette =
                    destinationPalette == null
                            ? null
                            : Collections.unmodifiableList(new ArrayList<>(destinationPalette));
        }
    }

    record Result(
            String world,
            BlockBounds bounds,
            List<DestinationPaletteEntry> destinationPalette,
            int seed,
            EditOutcome outcome,
            long volume,
            long changedBlockCount,
            EditRecord edit) {}
}
