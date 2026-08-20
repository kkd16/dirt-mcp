package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestDecoder;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks;
import java.io.IOException;
import java.util.Objects;

public final class GetRegionBlocksEndpoint implements BridgeEndpoint {
    private final GetRegionBlocks operation;
    private final RequestDecoder<GetRegionBlocks.Request> decoder;

    public GetRegionBlocksEndpoint(GetRegionBlocks operation, DirtConfig config) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.decoder = exchange -> GetRegionBlocksRequestDecoder.decode(exchange, config);
    }

    @Override
    public String operation() {
        return "get_region_blocks";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/get-region-blocks";
    }

    @Override
    public void handle(BridgeExchange exchange)
            throws IOException, InvalidRequestException, OperationException {
        GetRegionBlocks.Request request = this.decoder.decode(exchange);
        exchange.world(request.world());
        exchange.ok(this.operation.getRegionBlocks(request));
    }

    @Override
    public String internalErrorMessage() {
        return "The region's blocks could not be returned";
    }
}
