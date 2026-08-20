package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView;
import java.io.IOException;
import java.util.Objects;

public final class ScanOrthographicViewEndpoint implements BridgeEndpoint {
    private final ScanOrthographicView operation;
    private final DirtConfig config;

    public ScanOrthographicViewEndpoint(ScanOrthographicView operation, DirtConfig config) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public String operation() {
        return "scan_orthographic_view";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/scan-orthographic-view";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        ScanOrthographicView.Request request =
                ScanOrthographicViewRequestDecoder.decode(exchange, this.config);
        exchange.world(request.world());
        exchange.ok(this.operation.scanOrthographicView(request));
    }

    @Override
    public String internalErrorMessage() {
        return "The orthographic view could not be scanned";
    }
}
