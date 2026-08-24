package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface UndoEdits {
    Result undoEdits(Request request, UUID callId) throws OperationException;

    record Request(String world, List<UUID> editIds) {
        public Request {
            Objects.requireNonNull(world, "world");
            editIds = List.copyOf(editIds);
            for (int index = 0; index < editIds.size(); index++) {
                UuidV4.require(editIds.get(index), "editIds[" + index + "]");
            }
        }
    }

    sealed interface Result permits Completed, Partial {}

    record Completed(String world, List<EditRecord> undoneEdits, UUID undoCallId, Instant undoneAt)
            implements Result {
        public Completed {
            Objects.requireNonNull(world, "world");
            undoneEdits = List.copyOf(undoneEdits);
            if (undoneEdits.isEmpty()) {
                throw new IllegalArgumentException("A completed undo must contain edits");
            }
            UuidV4.require(undoCallId, "undoCallId");
            Objects.requireNonNull(undoneAt, "undoneAt");
        }
    }

    record Partial(
            String world, UUID undoCallId, List<EditRecord> undoneEdits, OperationException failure)
            implements Result {
        public Partial {
            Objects.requireNonNull(world, "world");
            UuidV4.require(undoCallId, "undoCallId");
            undoneEdits = List.copyOf(undoneEdits);
            Objects.requireNonNull(failure, "failure");
            if (failure.editId().isEmpty()) {
                throw new IllegalArgumentException("A partial undo failure must identify an edit");
            }
            if (failure.failure() != OperationFailure.INTERNAL_ERROR
                    && failure.failure() != OperationFailure.WORLD_UNAVAILABLE) {
                throw new IllegalArgumentException(
                        "A partial undo must be internal_error or world_unavailable");
            }
        }
    }
}
