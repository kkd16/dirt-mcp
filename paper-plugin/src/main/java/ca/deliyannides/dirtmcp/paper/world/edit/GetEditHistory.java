package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface GetEditHistory {
    Result getEditHistory(Request request) throws OperationException;

    record Request(String world) {}

    record Result(String world, List<EditRecord> edits) {
        public Result {
            if (world == null || world.isBlank()) {
                throw new IllegalArgumentException("world must be a non-empty string");
            }
            edits = List.copyOf(edits);
            Set<UUID> editIds = new HashSet<>();
            for (EditRecord edit : edits) {
                if (!world.equals(edit.world()) || !editIds.add(edit.editId())) {
                    throw new IllegalArgumentException(
                            "History records must belong to the response world and have unique IDs");
                }
            }
        }
    }
}
