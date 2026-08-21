package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.FluidCollision;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.LocationSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.PlayerSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.Source;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView.ViewRequest;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.List;
import java.util.Set;

final class GetPerspectiveViewRequestDecoder {
    private static final Set<String> REQUEST_FIELDS =
            Set.of(
                    "source",
                    "width",
                    "height",
                    "verticalFieldOfViewDegrees",
                    "maxDistance",
                    "fluidCollision",
                    "ignorePassableBlocks");
    private static final Set<String> PLAYER_SOURCE_FIELDS = Set.of("type", "player");
    private static final Set<String> LOCATION_SOURCE_FIELDS =
            Set.of("type", "world", "cameraPosition", "rotation");
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");
    private static final Set<String> ROTATION_FIELDS = Set.of("yaw", "pitch");

    private GetPerspectiveViewRequestDecoder() {}

    static GetPerspectiveView.Request decode(BridgeExchange exchange)
            throws IOException, OperationException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireFields(object, Set.of("source"), REQUEST_FIELDS, "Request");
        Source source = source(object.get("source"));
        ViewRequest view =
                new ViewRequest(
                        optionalInteger(object, "width", 21),
                        optionalInteger(object, "height", 13),
                        optionalInteger(object, "verticalFieldOfViewDegrees", 70),
                        optionalInteger(object, "maxDistance", 32),
                        fluidCollision(
                                object.has("fluidCollision")
                                        ? RequestJson.string(
                                                object.get("fluidCollision"), "fluidCollision")
                                        : "never"),
                        object.has("ignorePassableBlocks")
                                ? RequestJson.bool(
                                        object.get("ignorePassableBlocks"), "ignorePassableBlocks")
                                : false);
        return new GetPerspectiveView.Request(source, view);
    }

    private static Source source(JsonElement element) throws OperationException {
        JsonObject object = object(element, "source");
        String type = RequestJson.string(object.get("type"), "source.type");
        return switch (type) {
            case "player" -> playerSource(object);
            case "location" -> locationSource(object);
            default ->
                    throw RequestJson.invalid(
                            "source.type must be player or location",
                            new ErrorDetails.InvalidRequest.UnsupportedValue(
                                    "source.type", List.of("player", "location")));
        };
    }

    private static PlayerSource playerSource(JsonObject object) throws OperationException {
        RequestJson.requireExactFields(object, PLAYER_SOURCE_FIELDS, "source");
        return new PlayerSource(RequestJson.string(object.get("player"), "source.player"));
    }

    private static LocationSource locationSource(JsonObject object) throws OperationException {
        RequestJson.requireExactFields(object, LOCATION_SOURCE_FIELDS, "source");
        JsonObject position = object(object.get("cameraPosition"), "source.cameraPosition");
        RequestJson.requireExactFields(position, POSITION_FIELDS, "source.cameraPosition");
        JsonObject rotation = object(object.get("rotation"), "source.rotation");
        RequestJson.requireExactFields(rotation, ROTATION_FIELDS, "source.rotation");
        return new LocationSource(
                RequestJson.string(object.get("world"), "source.world"),
                new ExactPosition(
                        number(position.get("x"), "source.cameraPosition.x"),
                        number(position.get("y"), "source.cameraPosition.y"),
                        number(position.get("z"), "source.cameraPosition.z")),
                new Rotation(
                        number(rotation.get("yaw"), "source.rotation.yaw"),
                        number(rotation.get("pitch"), "source.rotation.pitch")));
    }

    private static JsonObject object(JsonElement element, String name) throws OperationException {
        if (element == null || !element.isJsonObject()) {
            throw RequestJson.invalid(
                    name + " must be an object",
                    new ErrorDetails.InvalidRequest.InvalidValue(name));
        }
        return element.getAsJsonObject();
    }

    private static double number(JsonElement element, String name) throws OperationException {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw invalidNumber(name);
        }
        try {
            double value = primitive.getAsDouble();
            if (!Double.isFinite(value)) {
                throw invalidNumber(name);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw invalidNumber(name);
        }
    }

    private static OperationException invalidNumber(String name) {
        return RequestJson.invalid(
                name + " must be a finite number",
                new ErrorDetails.InvalidRequest.InvalidValue(name));
    }

    private static int optionalInteger(JsonObject object, String field, int defaultValue)
            throws OperationException {
        return object.has(field) ? RequestJson.integer(object.get(field), field) : defaultValue;
    }

    private static FluidCollision fluidCollision(String value) throws OperationException {
        return switch (value) {
            case "never" -> FluidCollision.NEVER;
            case "source_only" -> FluidCollision.SOURCE_ONLY;
            case "always" -> FluidCollision.ALWAYS;
            default ->
                    throw RequestJson.invalid(
                            "fluidCollision must be never, source_only, or always",
                            new ErrorDetails.InvalidRequest.UnsupportedValue(
                                    "fluidCollision", List.of("never", "source_only", "always")));
        };
    }
}
