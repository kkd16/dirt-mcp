package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface GetEditHistory {
    Result getEditHistory(Request request) throws OperationException;

    record Request(String world) {
        public Request {
            Objects.requireNonNull(world, "world");
        }
    }

    record Result(String world, List<EditRecord> edits) {}
}
