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
    public String operation() {
        return "run_minecraft_commands";
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
        RunMinecraftCommands.Request request = RunMinecraftCommandsRequestDecoder.decode(exchange);
        exchange.requiredCallId();
        exchange.ok(this.operation.runCommands(request));
    }

    @Override
    public String internalErrorMessage() {
        return "The commands could not be run";
    }
}
