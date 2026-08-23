package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface UndoEdits {
    Result undoEdits(Request request, UUID callId) throws OperationException;

    record Request(String world, List<UUID> editIds) {
        public Request {
            if (editIds != null) {
                List<UUID> copy = new ArrayList<>(editIds);
                for (int index = 0; index < copy.size(); index++) {
                    UuidV4.require(copy.get(index), "editIds[" + index + "]");
                }
                editIds = Collections.unmodifiableList(copy);
            }
        }
    }

    record Result(String world, List<EditRecord> edits, UUID undoCallId, Instant undoneAt) {
        public Result {
            edits = List.copyOf(edits);
            UuidV4.require(undoCallId, "undoCallId");
        }
    }
}
