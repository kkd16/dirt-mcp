package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Includes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

final class GetPlayerContextRequestDecoder {
    private static final Set<String> REQUEST_FIELDS = Set.of("player", "include");
    private static final Set<String> INCLUDE_FIELDS =
            Set.of(
                    "equipment",
                    "inventory",
                    "enderChest",
                    "vitals",
                    "movement",
                    "client",
                    "effects");

    private GetPlayerContextRequestDecoder() {}

    static GetPlayerContext.Request decode(BridgeExchange exchange)
            throws IOException, OperationException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireExactFields(object, REQUEST_FIELDS, "Request");
        String player = RequestJson.string(object.get("player"), "player");
        Includes include = include(object.get("include"));
        return new GetPlayerContext.Request(player, include);
    }

    private static Includes include(JsonElement element) throws OperationException {
        JsonObject object = object(element, "include");
        RequestJson.requireExactFields(object, INCLUDE_FIELDS, "include");
        return new Includes(
                RequestJson.bool(object.get("equipment"), "include.equipment"),
                RequestJson.bool(object.get("inventory"), "include.inventory"),
                RequestJson.bool(object.get("enderChest"), "include.enderChest"),
                RequestJson.bool(object.get("vitals"), "include.vitals"),
                RequestJson.bool(object.get("movement"), "include.movement"),
                RequestJson.bool(object.get("client"), "include.client"),
                RequestJson.bool(object.get("effects"), "include.effects"));
    }

    private static JsonObject object(JsonElement element, String name) throws OperationException {
        if (element == null || !element.isJsonObject()) {
            throw RequestJson.invalid(
                    name + " must be an object",
                    new ErrorDetails.InvalidRequest.InvalidValue(name));
        }
        return element.getAsJsonObject();
    }
}
