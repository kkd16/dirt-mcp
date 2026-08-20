package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface UndoEdit {
    Result undoEdit(Request request, UUID callId) throws OperationException;

    record Request(String world, UUID editId) {}

    record Result(EditRecord edit, UUID undoCallId, String undoneAt) {
        public Result {
            Objects.requireNonNull(edit, "edit");
            Objects.requireNonNull(undoCallId, "undoCallId");
            try {
                undoneAt = Instant.parse(undoneAt).toString();
            } catch (DateTimeParseException | NullPointerException exception) {
                throw new IllegalArgumentException(
                        "undoneAt must be an ISO-8601 instant", exception);
            }
        }
    }
}
