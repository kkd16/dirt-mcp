package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

public record EditRecord(
        UUID editId,
        UUID callId,
        EditOperation operation,
        String world,
        UUID worldId,
        BlockBounds bounds,
        long changedBlockCount,
        String completedAt,
        EditStatus status) {
    public EditRecord {
        Objects.requireNonNull(editId, "editId");
        Objects.requireNonNull(callId, "callId");
        if (editId.version() != 4 || editId.variant() != 2) {
            throw new IllegalArgumentException("editId must be a UUID version 4");
        }
        if (callId.version() != 4 || callId.variant() != 2) {
            throw new IllegalArgumentException("callId must be a UUID version 4");
        }
        Objects.requireNonNull(operation, "operation");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world must be a non-empty string");
        }
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(bounds, "bounds");
        if (changedBlockCount < 1) {
            throw new IllegalArgumentException("changedBlockCount must be positive");
        }
        Objects.requireNonNull(status, "status");
        try {
            completedAt = Instant.parse(completedAt).toString();
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalArgumentException(
                    "completedAt must be an ISO-8601 instant", exception);
        }
    }

    EditRecord withStatus(EditStatus replacement) {
        return new EditRecord(
                this.editId,
                this.callId,
                this.operation,
                this.world,
                this.worldId,
                this.bounds,
                this.changedBlockCount,
                this.completedAt,
                replacement);
    }

    static void validateResult(
            EditOutcome outcome,
            EditRecord edit,
            EditOperation operation,
            String world,
            BlockBounds bounds,
            long changedBlockCount) {
        Objects.requireNonNull(outcome, "outcome");
        boolean committed = outcome == EditOutcome.COMMITTED;
        if (committed != (edit != null)) {
            throw new IllegalArgumentException(
                    "Only a committed edit outcome may contain edit metadata");
        }
        if (edit != null
                && (edit.operation() != operation
                        || !edit.world().equals(world)
                        || !edit.bounds().equals(bounds)
                        || edit.changedBlockCount() != changedBlockCount
                        || edit.status() != EditStatus.COMMITTED)) {
            throw new IllegalArgumentException("Edit result metadata does not match its outcome");
        }
    }
}
