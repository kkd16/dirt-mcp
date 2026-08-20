package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@FunctionalInterface
public interface SetBlocks {
    Result setBlocks(Request request) throws OperationException;

    record Offset(int x, int y, int z) {}

    record Placement(int paletteIndex, List<Offset> offsets) {
        public Placement {
            offsets =
                    offsets == null ? null : Collections.unmodifiableList(new ArrayList<>(offsets));
        }
    }

    record Request(
            String world,
            BlockPosition origin,
            List<String> palette,
            List<Placement> placements,
            boolean dryRun) {
        public Request {
            palette =
                    palette == null ? null : Collections.unmodifiableList(new ArrayList<>(palette));
            placements =
                    placements == null
                            ? null
                            : Collections.unmodifiableList(new ArrayList<>(placements));
        }
    }

    record Result(
            String world,
            boolean dryRun,
            long blockCount,
            long changedBlockCount,
            long unchangedBlockCount) {}
}
