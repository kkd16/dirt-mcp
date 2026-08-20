package ca.deliyannides.dirtmcp.paper.bridge;

import java.io.Serial;

public final class InvalidRequestException extends Exception {
    @Serial private static final long serialVersionUID = 1L;

    public InvalidRequestException(String message) {
        super(message);
    }
}
