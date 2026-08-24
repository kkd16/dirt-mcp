package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import java.util.List;
import java.util.Objects;

/** Replay-ready exact block geometry shared by inspection operations. */
public record ExactBlockStructure(
        String world,
        BlockPosition origin,
        List<List<ExactPaletteEntry>> palettes,
        List<Placement> placements,
        List<Run> runs) {
    public ExactBlockStructure {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(palettes, "palettes");
        palettes = palettes.stream().map(List::copyOf).toList();
        placements = List.copyOf(Objects.requireNonNull(placements, "placements"));
        runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
    }

    public long blockCount() {
        long count = placements.size();
        for (Run run : runs) {
            long sizeX = (long) run.toX() - run.x() + 1;
            long sizeY = (long) run.toY() - run.y() + 1;
            long sizeZ = (long) run.toZ() - run.z() + 1;
            count =
                    Math.addExact(
                            count, Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ));
        }
        return count;
    }

    public record ExactPaletteEntry(String blockState) {
        public ExactPaletteEntry {
            Objects.requireNonNull(blockState, "blockState");
        }
    }
}
