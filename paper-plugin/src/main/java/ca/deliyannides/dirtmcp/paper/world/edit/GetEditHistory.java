package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.util.List;

@FunctionalInterface
public interface GetEditHistory {
    Result getEditHistory(Request request) throws OperationException;

    record Request(String world) {}

    record Result(String world, List<EditRecord> edits) {
        public Result {
            edits = List.copyOf(edits);
        }
    }
}
