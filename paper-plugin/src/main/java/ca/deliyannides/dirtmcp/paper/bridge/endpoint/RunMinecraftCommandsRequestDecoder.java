package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

final class RunMinecraftCommandsRequestDecoder {
    private static final Set<String> FIELDS = Set.of("commands");

    private RunMinecraftCommandsRequestDecoder() {}

    static RunMinecraftCommands.Request decode(BridgeExchange exchange)
            throws IOException, OperationException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireExactFields(object, FIELDS, "Request");
        return new RunMinecraftCommands.Request(
                RequestJson.nonEmptyStringList(object.get("commands"), "commands"));
    }
}
