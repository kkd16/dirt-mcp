package ca.deliyannides.dirtmcp.paper.operation;

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
        if (editId != null && (editId.version() != 4 || editId.variant() != 2)) {
            throw new IllegalArgumentException("editId must be a UUID version 4");
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
