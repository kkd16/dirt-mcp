package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestDecoder;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.ReplaceRegionBlocks;
import java.io.IOException;
import java.util.Objects;

public final class ReplaceRegionBlocksEndpoint implements BridgeEndpoint {
    private final ReplaceRegionBlocks operation;
    private final RequestDecoder<ReplaceRegionBlocks.Request> decoder;

    public ReplaceRegionBlocksEndpoint(ReplaceRegionBlocks operation, DirtConfig config) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.decoder = exchange -> ReplaceRegionBlocksRequestDecoder.decode(exchange, config);
    }

    @Override
    public String operation() {
        return "replace_region_blocks";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/replace-region-blocks";
    }

    @Override
    public void handle(BridgeExchange exchange)
            throws IOException, InvalidRequestException, OperationException {
        ReplaceRegionBlocks.Request request = this.decoder.decode(exchange);
        exchange.world(request.world());
        exchange.ok(this.operation.replaceRegionBlocks(request));
    }

    @Override
    public String internalErrorMessage() {
        return "The blocks could not be replaced";
    }
}
