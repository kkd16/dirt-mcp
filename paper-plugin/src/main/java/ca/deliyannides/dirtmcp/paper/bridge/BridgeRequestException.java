package ca.deliyannides.dirtmcp.paper.bridge;

import java.io.Serial;
import java.util.Objects;

/** Stops endpoint dispatch when bridge-owned HTTP request validation fails. */
final class BridgeRequestException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    private final BridgeProblem problem;

    BridgeRequestException(String message, BridgeProblem problem) {
        super(message);
        this.problem = Objects.requireNonNull(problem, "problem");
    }

    BridgeProblem problem() {
        return this.problem;
    }
}
