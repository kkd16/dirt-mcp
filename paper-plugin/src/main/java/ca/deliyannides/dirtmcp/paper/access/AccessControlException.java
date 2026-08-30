package ca.deliyannides.dirtmcp.paper.access;

import java.io.Serial;

/** Sanitized failure from the loopback access-control service. */
public final class AccessControlException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    private final Reason reason;

    AccessControlException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    AccessControlException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return this.reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        UNAUTHORIZED,
        NOT_FOUND,
        CONFLICT,
        UNAVAILABLE,
        TIMEOUT,
        PROTOCOL_ERROR
    }
}
