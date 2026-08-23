package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdits;
import java.io.IOException;
import java.util.Objects;

public final class UndoEditsEndpoint implements BridgeEndpoint {
    private final UndoEdits operation;

    public UndoEditsEndpoint(UndoEdits operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operation() {
        return "undo_edits";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/undo-edits";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        UndoEdits.Request request = UndoEditsRequestDecoder.decode(exchange);
        exchange.world(request.world());
        exchange.ok(this.operation.undoEdits(request, exchange.requiredCallId()));
    }

    @Override
    public String internalErrorMessage() {
        return "The edits could not be undone";
    }
}
