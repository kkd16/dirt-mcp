package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestDecoder;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.GetEditHistory;
import java.io.IOException;
import java.util.Objects;

public final class GetEditHistoryEndpoint implements BridgeEndpoint {
    private final GetEditHistory operation;
    private final RequestDecoder<GetEditHistory.Request> decoder;

    public GetEditHistoryEndpoint(GetEditHistory operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.decoder = GetEditHistoryRequestDecoder::decode;
    }

    @Override
    public String operation() {
        return "get_edit_history";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/get-edit-history";
    }

    @Override
    public void handle(BridgeExchange exchange)
            throws IOException, InvalidRequestException, OperationException {
        GetEditHistory.Request request = this.decoder.decode(exchange);
        exchange.world(request.world());
        exchange.ok(this.operation.getEditHistory(request));
    }

    @Override
    public String internalErrorMessage() {
        return "Edit history could not be read";
    }
}
