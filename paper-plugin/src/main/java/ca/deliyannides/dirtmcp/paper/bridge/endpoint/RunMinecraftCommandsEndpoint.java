package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.io.IOException;
import java.util.Objects;

public final class RunMinecraftCommandsEndpoint implements BridgeEndpoint {
    private final RunMinecraftCommands operation;

    public RunMinecraftCommandsEndpoint(RunMinecraftCommands operation) {
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public String operationId() {
        return "runMinecraftCommands";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/v1/run-minecraft-commands";
    }

    @Override
    public void handle(BridgeExchange exchange) throws IOException, OperationException {
        exchange.suppressFailureDetails();
        RunMinecraftCommands.Request request = RunMinecraftCommandsRequestDecoder.decode(exchange);
        RunMinecraftCommands.Result result = this.operation.runCommands(request);
        boolean allDispatched =
                result.results().getLast().outcome() == RunMinecraftCommands.Outcome.DISPATCHED;
        exchange.auditField("outcome", allDispatched ? "dispatched" : "partial_failure");
        exchange.auditField("result_count", result.results().size());
        exchange.auditCompletion(
                allDispatched ? BridgeExchange.AuditLevel.INFO : BridgeExchange.AuditLevel.WARNING,
                true);
        exchange.ok(result);
    }

    @Override
    public String internalErrorMessage() {
        return "The commands could not be run";
    }
}
