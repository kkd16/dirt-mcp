package ca.deliyannides.dirtmcp.paper.bridge;

import java.io.IOException;
import java.io.Serial;

final class RequestTimeoutException extends IOException {
    @Serial private static final long serialVersionUID = 1L;

    RequestTimeoutException(String message) {
        super(message);
    }
}
