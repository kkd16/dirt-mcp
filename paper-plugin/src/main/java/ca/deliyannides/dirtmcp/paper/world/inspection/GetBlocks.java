package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface GetBlocks {
    Result getBlocks(Request request) throws OperationException;

    record Request(
            String world,
            BlockPosition min,
            BlockPosition max,
            List<String> includeBlockStatePatterns,
            List<String> excludeBlockStatePatterns,
            boolean includeAir,
            int maxResults) {
        public Request {
            includeBlockStatePatterns = immutableCopy(includeBlockStatePatterns);
            excludeBlockStatePatterns = immutableCopy(excludeBlockStatePatterns);
        }
    }

    record ExactPaletteEntry(String blockState) {
        public ExactPaletteEntry {
            Objects.requireNonNull(blockState, "blockState");
        }
    }

    record Result(
            String world,
            BlockPosition origin,
            List<List<ExactPaletteEntry>> palettes,
            List<Placement> placements,
            List<Run> runs) {
        public Result {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(origin, "origin");
            palettes = immutablePalettes(palettes);
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
    }

    private static List<String> immutableCopy(List<String> values) {
        // Preserve malformed null entries for operation-layer validation.
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<List<ExactPaletteEntry>> immutablePalettes(
            List<List<ExactPaletteEntry>> palettes) {
        Objects.requireNonNull(palettes, "palettes");
        return palettes.stream().map(List::copyOf).toList();
    }
}
