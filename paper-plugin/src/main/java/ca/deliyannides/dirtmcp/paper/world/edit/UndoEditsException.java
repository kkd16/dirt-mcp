package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A batch undo failure after execution began, including its successfully consumed prefix. */
public final class UndoEditsException extends OperationException {
    @Serial private static final long serialVersionUID = 1L;

    private final transient List<EditRecord> undoneEdits;

    public UndoEditsException(
            OperationFailure failure,
            String message,
            ErrorDetails details,
            Throwable cause,
            UUID failedEditId,
            List<EditRecord> undoneEdits) {
        super(
                failure,
                message,
                details,
                cause,
                Objects.requireNonNull(failedEditId, "failedEditId"));
        this.undoneEdits = List.copyOf(undoneEdits);
    }

    public List<EditRecord> undoneEdits() {
        return this.undoneEdits;
    }
}
