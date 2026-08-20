package ca.deliyannides.dirtmcp.paper.operation;

import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import java.io.Serial;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class OperationException extends Exception {
    @Serial private static final long serialVersionUID = 1L;

    private final OperationFailure failure;
    private final UUID editId;

    public OperationException(OperationFailure failure, String message) {
        this(failure, message, null, null);
    }

    public OperationException(OperationFailure failure, String message, Throwable cause) {
        this(failure, message, cause, null);
    }

    public OperationException(
            OperationFailure failure, String message, Throwable cause, UUID editId) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
        if (editId != null) {
            UuidV4.require(editId, "editId");
        }
        this.editId = editId;
    }

    public OperationFailure failure() {
        return this.failure;
    }

    public Optional<UUID> editId() {
        return Optional.ofNullable(this.editId);
    }
}
