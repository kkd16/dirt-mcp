package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdit;
import java.io.IOException;
import java.util.Objects;

public final class UndoEditEndpoint implements BridgeEndpoint {
    private final UndoEdit operation;

    public UndoEditEndpoint(UndoEdit operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operation() {
        return "undo_edit";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/undo-edit";
    }

    @Override
    public void handle(BridgeExchange exchange)
            throws IOException, InvalidRequestException, OperationException {
        UndoEdit.Request request = UndoEditRequestDecoder.decode(exchange);
        exchange.world(request.world());
        exchange.ok(this.operation.undoEdit(request, exchange.requiredCallId()));
    }

    @Override
    public String internalErrorMessage() {
        return "The edit could not be undone";
    }
}
