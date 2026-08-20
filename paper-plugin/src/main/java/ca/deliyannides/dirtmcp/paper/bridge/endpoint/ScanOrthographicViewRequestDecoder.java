package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

final class ScanOrthographicViewRequestDecoder {
    private static final Set<String> REQUIRED_FIELDS =
            Set.of(
                    "world",
                    "origin",
                    "direction",
                    "horizontalRadius",
                    "verticalRadius",
                    "maxDistance");
    private static final Set<String> ALLOWED_FIELDS =
            Set.of(
                    "world",
                    "origin",
                    "direction",
                    "horizontalRadius",
                    "verticalRadius",
                    "maxDistance",
                    "maxResults");

    private ScanOrthographicViewRequestDecoder() {}

    static ScanOrthographicView.Request decode(BridgeExchange exchange, DirtConfig config)
            throws IOException, InvalidRequestException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireFields(object, REQUIRED_FIELDS, ALLOWED_FIELDS);
        int horizontalRadius =
                RequestJson.integer(object.get("horizontalRadius"), "horizontalRadius");
        int verticalRadius = RequestJson.integer(object.get("verticalRadius"), "verticalRadius");
        int maxDistance = RequestJson.integer(object.get("maxDistance"), "maxDistance");
        int maxResults =
                object.has("maxResults")
                        ? RequestJson.integer(object.get("maxResults"), "maxResults")
                        : config.limits().defaultInspectionResultLimit();
        return new ScanOrthographicView.Request(
                RequestJson.string(object.get("world"), "world"),
                RequestJson.position(object.get("origin"), "origin"),
                direction(object.get("direction")),
                horizontalRadius,
                verticalRadius,
                maxDistance,
                maxResults);
    }

    private static ScanOrthographicView.Direction direction(JsonElement element)
            throws InvalidRequestException {
        return switch (RequestJson.string(element, "direction")) {
            case "north" -> ScanOrthographicView.Direction.NORTH;
            case "east" -> ScanOrthographicView.Direction.EAST;
            case "south" -> ScanOrthographicView.Direction.SOUTH;
            case "west" -> ScanOrthographicView.Direction.WEST;
            case "up" -> ScanOrthographicView.Direction.UP;
            case "down" -> ScanOrthographicView.Direction.DOWN;
            default ->
                    throw new InvalidRequestException(
                            "direction must be north, east, south, west, up, or down");
        };
    }
}
