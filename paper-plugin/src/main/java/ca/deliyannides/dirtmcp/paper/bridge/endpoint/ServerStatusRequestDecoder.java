package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

final class ServerStatusRequestDecoder {
    private static final Set<String> FIELDS =
            Set.of("includePlayers", "includeWorlds", "includeConfiguration");

    private ServerStatusRequestDecoder() {}

    static GetServerStatus.Request decode(BridgeExchange exchange)
            throws IOException, OperationException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireExactFields(object, FIELDS, "Request");
        return new GetServerStatus.Request(
                RequestJson.bool(object.get("includePlayers"), "includePlayers"),
                RequestJson.bool(object.get("includeWorlds"), "includeWorlds"),
                RequestJson.bool(object.get("includeConfiguration"), "includeConfiguration"));
    }
}
