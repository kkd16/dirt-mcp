package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface SetBlocks {
    Result setBlocks(Request request, UUID callId) throws OperationException;

    record Placement(int paletteIndex, int x, int y, int z) {}

    record Request(
            String world,
            BlockPosition origin,
            List<List<DestinationPaletteEntry>> palettes,
            List<Placement> placements,
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
            EditRecord edit) {
        public Result {
            palettes = immutablePalettes(palettes);
            if (blockCount < 1
                    || changedBlockCount < 0
                    || unchangedBlockCount < 0
                    || changedBlockCount > blockCount
                    || unchangedBlockCount != blockCount - changedBlockCount) {
                throw new IllegalArgumentException(
                        "changed and unchanged block counts must partition blockCount");
            }
            EditRecord.validateResult(
                    outcome, edit, EditOperation.SET_BLOCKS, world, bounds, changedBlockCount);
        }
    }

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
