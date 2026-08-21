package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface SetBlocks {
    Result setBlocks(Request request, UUID callId) throws OperationException;

    record Request(
            String world,
            BlockPosition origin,
            List<List<DestinationPaletteEntry>> palettes,
            List<Placement> placements,
            List<Run> runs,
            int seed,
            boolean dryRun) {
        public Request {
            if (palettes != null) {
                palettes = immutablePalettes(palettes);
            }
            placements =
                    placements == null
                            ? null
                            : Collections.unmodifiableList(new ArrayList<>(placements));
            runs = runs == null ? null : Collections.unmodifiableList(new ArrayList<>(runs));
        }
    }

    record Result(
            String world,
            BlockBounds bounds,
            List<List<DestinationPaletteEntry>> palettes,
            int seed,
            EditOutcome outcome,
            long blockCount,
            long changedBlockCount,
            long unchangedBlockCount,
            EditRecord edit) {}

    private static List<List<DestinationPaletteEntry>> immutablePalettes(
            List<List<DestinationPaletteEntry>> palettes) {
        // Preserve malformed null entries for operation-layer INVALID_REQUEST reporting.
        List<List<DestinationPaletteEntry>> copy = new ArrayList<>(palettes.size());
        for (List<DestinationPaletteEntry> palette : palettes) {
            copy.add(
                    palette == null
                            ? null
                            : Collections.unmodifiableList(new ArrayList<>(palette)));
        }
        return Collections.unmodifiableList(copy);
    }
}
