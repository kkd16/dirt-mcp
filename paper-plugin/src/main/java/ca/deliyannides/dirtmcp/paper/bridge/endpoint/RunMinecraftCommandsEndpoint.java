package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestDecoder;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.io.IOException;
import java.util.Objects;

public final class RunMinecraftCommandsEndpoint implements BridgeEndpoint {
    private final RunMinecraftCommands operation;
    private final RequestDecoder<RunMinecraftCommands.Request> decoder;

    public RunMinecraftCommandsEndpoint(RunMinecraftCommands operation) {
        this(operation, RunMinecraftCommandsRequestDecoder::decode);
    }

    RunMinecraftCommandsEndpoint(
            RunMinecraftCommands operation, RequestDecoder<RunMinecraftCommands.Request> decoder) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
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
    public void handle(BridgeExchange exchange)
            throws IOException, InvalidRequestException, OperationException {
        exchange.ok(this.operation.runCommands(this.decoder.decode(exchange)));
    }

    @Override
    public String internalErrorMessage() {
        return "The commands could not be run";
    }
}
