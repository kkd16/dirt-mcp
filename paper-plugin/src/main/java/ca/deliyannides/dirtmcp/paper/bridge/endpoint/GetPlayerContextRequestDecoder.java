package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.FluidCollision;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.Includes;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext.ViewRequest;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Set;

final class GetPlayerContextRequestDecoder {
    private static final Set<String> REQUEST_FIELDS = Set.of("player", "include", "view");
    private static final Set<String> INCLUDE_FIELDS =
            Set.of(
                    "view",
                    "equipment",
                    "inventory",
                    "enderChest",
                    "vitals",
                    "movement",
                    "client",
                    "effects");
    private static final Set<String> VIEW_FIELDS =
            Set.of(
                    "width",
                    "height",
                    "verticalFieldOfViewDegrees",
                    "maxDistance",
                    "fluidCollision",
                    "ignorePassableBlocks");

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
        if (!include.view() && object.has("view")) {
            throw RequestJson.invalid(
                    "view must be omitted when include.view is false",
                    new ErrorDetails.InvalidRequest.InvalidValue("view"));
        }
        ViewRequest view = include.view() ? view(object.get("view")) : null;
        return new GetPlayerContext.Request(player, include, view);
    }

    private static Includes include(JsonElement element) throws OperationException {
        JsonObject object = object(element, "include");
        RequestJson.requireFields(object, Set.of(), INCLUDE_FIELDS, "include");
        return new Includes(
                optionalBoolean(object, "view", true, "include.view"),
                optionalBoolean(object, "equipment", true, "include.equipment"),
                optionalBoolean(object, "inventory", false, "include.inventory"),
                optionalBoolean(object, "enderChest", false, "include.enderChest"),
                optionalBoolean(object, "vitals", false, "include.vitals"),
                optionalBoolean(object, "movement", false, "include.movement"),
                optionalBoolean(object, "client", false, "include.client"),
                optionalBoolean(object, "effects", false, "include.effects"));
    }

    private static ViewRequest view(JsonElement element) throws OperationException {
        JsonObject object;
        if (element == null) {
            object = new JsonObject();
        } else {
            object = object(element, "view");
            RequestJson.requireFields(object, Set.of(), VIEW_FIELDS, "view");
        }
        return new ViewRequest(
                optionalInteger(object, "width", 21),
                optionalInteger(object, "height", 13),
                optionalInteger(object, "verticalFieldOfViewDegrees", 70),
                optionalInteger(object, "maxDistance", 32),
                fluidCollision(
                        object.has("fluidCollision")
                                ? RequestJson.string(
                                        object.get("fluidCollision"), "view.fluidCollision")
                                : "never"),
                optionalBoolean(
                        object, "ignorePassableBlocks", false, "view.ignorePassableBlocks"));
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

    private static int optionalInteger(JsonObject object, String field, int defaultValue)
            throws OperationException {
        return object.has(field)
                ? RequestJson.integer(object.get(field), "view." + field)
                : defaultValue;
    }

    private static Includes defaults() {
        return new Includes(true, true, false, false, false, false, false, false);
    }

    private static FluidCollision fluidCollision(String value) throws OperationException {
        return switch (value) {
            case "never" -> FluidCollision.NEVER;
            case "source_only" -> FluidCollision.SOURCE_ONLY;
            case "always" -> FluidCollision.ALWAYS;
            default ->
                    throw RequestJson.invalid(
                            "view.fluidCollision must be never, source_only, or always",
                            new ErrorDetails.InvalidRequest.UnsupportedValue(
                                    "view.fluidCollision",
                                    List.of("never", "source_only", "always")));
        };
    }
}
