package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface UndoEdit {
    Result undoEdit(Request request, UUID callId) throws OperationException;

    record Request(String world, UUID editId) {
        public Request {
            UuidV4.require(editId, "editId");
        }
    }

    record Result(EditRecord edit, UUID undoCallId, Instant undoneAt) {}
}
