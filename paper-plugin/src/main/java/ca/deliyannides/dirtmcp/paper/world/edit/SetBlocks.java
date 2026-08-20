package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@FunctionalInterface
public interface SetBlocks {
    Result setBlocks(Request request) throws OperationException;

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
            List<List<DestinationPaletteEntry>> palettes,
            int seed,
            boolean dryRun,
            long blockCount,
            long changedBlockCount,
            long unchangedBlockCount) {
        public Result {
            if (palettes != null) {
                palettes = immutablePalettes(palettes);
            }
        }
    }

    private static List<List<DestinationPaletteEntry>> immutablePalettes(
            List<List<DestinationPaletteEntry>> palettes) {
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
