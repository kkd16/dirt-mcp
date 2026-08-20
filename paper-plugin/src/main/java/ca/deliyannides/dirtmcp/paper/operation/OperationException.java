package ca.deliyannides.dirtmcp.paper.operation;

import java.io.Serial;
import java.util.Objects;

public final class OperationException extends Exception {
    @Serial private static final long serialVersionUID = 1L;

    private final OperationFailure failure;

    public OperationException(OperationFailure failure, String message) {
        super(message);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public OperationException(OperationFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public OperationFailure failure() {
        return this.failure;
    }
}
