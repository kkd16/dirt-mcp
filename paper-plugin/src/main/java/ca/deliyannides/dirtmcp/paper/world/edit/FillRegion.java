package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@FunctionalInterface
public interface FillRegion {
    Result fillRegion(Request request) throws OperationException;

    record Request(
            String world,
            BlockPosition min,
            BlockPosition max,
            List<DestinationPaletteEntry> destinationPalette,
            int seed,
            boolean dryRun) {
        public Request {
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
            boolean dryRun,
            long volume,
            long changedBlockCount) {
        public Result {
            destinationPalette = List.copyOf(destinationPalette);
        }
    }
}
