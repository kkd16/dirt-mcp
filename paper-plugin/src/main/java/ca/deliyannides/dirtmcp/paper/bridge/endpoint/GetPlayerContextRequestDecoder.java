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
        RequestJson.requireFields(object, Set.of("player"), REQUEST_FIELDS, "Request");
        String player = RequestJson.string(object.get("player"), "player");
        if (player.length() > GetPlayerContext.MAX_PLAYER_SELECTOR_LENGTH) {
            throw RequestJson.invalid(
                    "player must contain at most "
                            + GetPlayerContext.MAX_PLAYER_SELECTOR_LENGTH
                            + " characters",
                    new ErrorDetails.InvalidRequest.OutOfRange(
                            "player.length",
                            player.length(),
                            1,
                            GetPlayerContext.MAX_PLAYER_SELECTOR_LENGTH));
        }
        Includes include = object.has("include") ? include(object.get("include")) : defaults();
        return new GetPlayerContext.Request(player, include);
    }

    private static Includes include(JsonElement element) throws OperationException {
        JsonObject object = object(element, "include");
        RequestJson.requireFields(object, Set.of(), INCLUDE_FIELDS, "include");
        return new Includes(
                optionalBoolean(object, "equipment", true, "include.equipment"),
                optionalBoolean(object, "inventory", false, "include.inventory"),
                optionalBoolean(object, "enderChest", false, "include.enderChest"),
                optionalBoolean(object, "vitals", false, "include.vitals"),
                optionalBoolean(object, "movement", false, "include.movement"),
                optionalBoolean(object, "client", false, "include.client"),
                optionalBoolean(object, "effects", false, "include.effects"));
    }

    private static JsonObject object(JsonElement element, String name) throws OperationException {
        if (element == null || !element.isJsonObject()) {
            throw RequestJson.invalid(
                    name + " must be an object",
                    new ErrorDetails.InvalidRequest.InvalidValue(name));
        }
        return element.getAsJsonObject();
    }

    private static boolean optionalBoolean(
            JsonObject object, String field, boolean defaultValue, String target)
            throws OperationException {
        return object.has(field) ? RequestJson.bool(object.get(field), target) : defaultValue;
    }

    private static Includes defaults() {
        return new Includes(true, false, false, false, false, false, false);
    }
}
