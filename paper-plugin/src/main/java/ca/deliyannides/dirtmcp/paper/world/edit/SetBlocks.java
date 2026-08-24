package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
            boolean dryRun,
            String label,
            Integer maxChangedBlocks) {
        public Request {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(origin, "origin");
            palettes = immutablePalettes(palettes);
            placements = List.copyOf(placements);
            runs = List.copyOf(runs);
            Objects.requireNonNull(label, "label");
        }
    }

    record Result(
            String world,
            BlockBounds bounds,
            int seed,
            EditOutcome outcome,
            long blockCount,
            long changedBlockCount,
            long unchangedBlockCount,
            EditRecord edit) {}

    private static List<List<DestinationPaletteEntry>> immutablePalettes(
            List<List<DestinationPaletteEntry>> palettes) {
        Objects.requireNonNull(palettes, "palettes");
        List<List<DestinationPaletteEntry>> copy = new ArrayList<>(palettes.size());
        for (List<DestinationPaletteEntry> palette : palettes) {
            copy.add(List.copyOf(palette));
        }
        return List.copyOf(copy);
    }
}
