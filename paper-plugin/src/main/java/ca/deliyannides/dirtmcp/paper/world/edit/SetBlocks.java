package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@FunctionalInterface
public interface SetBlocks {
    Result setBlocks(Request request) throws OperationException;

    record Request(String world, List<BlockChange> changes, boolean dryRun) {
        public Request {
            changes =
                    changes == null ? null : Collections.unmodifiableList(new ArrayList<>(changes));
        }
    }

    record Result(
            String world,
            boolean dryRun,
            long blockCount,
            long changedBlockCount,
            long unchangedBlockCount) {}
}
