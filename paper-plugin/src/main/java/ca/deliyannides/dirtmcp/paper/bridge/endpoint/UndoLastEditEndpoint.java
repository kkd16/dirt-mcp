package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestDecoder;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoLastEdit;
import java.io.IOException;
import java.util.Objects;

public final class UndoLastEditEndpoint implements BridgeEndpoint {
    private final UndoLastEdit operation;
    private final RequestDecoder<UndoLastEdit.Request> decoder;

    public UndoLastEditEndpoint(UndoLastEdit operation) {
        this(operation, UndoLastEditRequestDecoder::decode);
    }

    UndoLastEditEndpoint(UndoLastEdit operation, RequestDecoder<UndoLastEdit.Request> decoder) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    @Override
    public String operation() {
        return "undo_last_dirt_edit";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/undo-last-dirt-edit";
    }

    @Override
    public void handle(BridgeExchange exchange)
            throws IOException, InvalidRequestException, OperationException {
        UndoLastEdit.Request request = this.decoder.decode(exchange);
        exchange.world(request.world());
        exchange.ok(this.operation.undoLastEdit(request));
    }

    @Override
    public String internalErrorMessage() {
        return "The edit could not be undone";
    }
}
