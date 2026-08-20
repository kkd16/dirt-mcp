package ca.deliyannides.dirtmcp.paper.platform;

import java.io.Serial;

public final class PaperMainThreadException extends Exception {
    @Serial private static final long serialVersionUID = 1L;

    public PaperMainThreadException(String message) {
        super(message);
    }

    public PaperMainThreadException(String message, Throwable cause) {
        super(message, cause);
    }
}
