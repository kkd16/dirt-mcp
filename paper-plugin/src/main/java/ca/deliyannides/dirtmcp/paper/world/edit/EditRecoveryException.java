package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.io.Serial;
import java.util.Objects;

final class EditRecoveryException extends OperationException {
    @Serial private static final long serialVersionUID = 1L;

    private final transient EditPlatform.UndoToken recovery;

    EditRecoveryException(
            String message,
            ErrorDetails.WorldUnavailable details,
            Throwable cause,
            EditPlatform.UndoToken recovery) {
        super(OperationFailure.WORLD_UNAVAILABLE, message, details, cause);
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    EditPlatform.UndoToken recovery() {
        return this.recovery;
    }
}
