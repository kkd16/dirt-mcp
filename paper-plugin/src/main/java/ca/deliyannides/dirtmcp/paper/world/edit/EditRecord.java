package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
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
        UuidV4.require(editId, "editId");
        UuidV4.require(callId, "callId");
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
        Objects.requireNonNull(operation, "operation");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world must be a non-empty string");
        }
        Objects.requireNonNull(bounds, "bounds");
        if (changedBlockCount < 0) {
            throw new IllegalArgumentException("changedBlockCount must be non-negative");
        }
        boolean committed = outcome == EditOutcome.COMMITTED;
        if (committed != (edit != null)) {
            throw new IllegalArgumentException(
                    "Only a committed edit outcome may contain edit metadata");
        }
        if ((committed && changedBlockCount == 0)
                || (outcome == EditOutcome.NO_CHANGE && changedBlockCount != 0)) {
            throw new IllegalArgumentException("Edit outcome does not match its change count");
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
