package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestDecoder;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.FillRegion;
import java.io.IOException;
import java.util.Objects;

public final class FillRegionEndpoint implements BridgeEndpoint {
    private final FillRegion operation;
    private final RequestDecoder<FillRegion.Request> decoder;

    public FillRegionEndpoint(FillRegion operation, DirtConfig config) {
        this(operation, exchange -> FillRegionRequestDecoder.decode(exchange, config));
    }

    FillRegionEndpoint(FillRegion operation, RequestDecoder<FillRegion.Request> decoder) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    @Override
    public String operation() {
        return "fill_region";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/fill-region";
    }

    @Override
    public void handle(BridgeExchange exchange)
            throws IOException, InvalidRequestException, OperationException {
        FillRegion.Request request = this.decoder.decode(exchange);
        exchange.world(request.world());
        exchange.ok(this.operation.fillRegion(request));
    }

    @Override
    public String internalErrorMessage() {
        return "The region could not be filled";
    }
}
