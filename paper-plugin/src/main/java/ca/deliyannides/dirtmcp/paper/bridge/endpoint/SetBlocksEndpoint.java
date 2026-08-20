package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import java.io.IOException;
import java.util.Objects;

public final class SetBlocksEndpoint implements BridgeEndpoint {
    private final SetBlocks operation;
    private final DirtConfig config;

    public SetBlocksEndpoint(SetBlocks operation, DirtConfig config) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String operation() {
        return "set_blocks";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/set-blocks";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        SetBlocks.Request request = SetBlocksRequestDecoder.decode(exchange, this.config);
        exchange.world(request.world());
        exchange.ok(this.operation.setBlocks(request, exchange.requiredCallId()));
    }

    @Override
    public String internalErrorMessage() {
        return "The blocks could not be set";
    }
}
