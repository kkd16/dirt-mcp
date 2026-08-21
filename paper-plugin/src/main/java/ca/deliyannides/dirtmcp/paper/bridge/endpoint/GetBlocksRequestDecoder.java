package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetBlocks;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Set;

final class GetBlocksRequestDecoder {
    private static final Set<String> REQUIRED_FIELDS = Set.of("world", "min", "max");
    private static final Set<String> ALLOWED_FIELDS =
            Set.of(
                    "world",
                    "min",
                    "max",
                    "includeBlockStatePatterns",
                    "excludeBlockStatePatterns",
                    "includeAir",
                    "maxResults");

    private GetBlocksRequestDecoder() {}

    static GetBlocks.Request decode(BridgeExchange exchange, DirtConfig config)
            throws IOException, OperationException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireFields(object, REQUIRED_FIELDS, ALLOWED_FIELDS, "Request");
        int maxResults =
                object.has("maxResults")
                        ? RequestJson.integer(object.get("maxResults"), "maxResults")
                        : config.limits().defaultInspectionResultLimit();
        return new GetBlocks.Request(
                RequestJson.string(object.get("world"), "world"),
                RequestJson.position(object.get("min"), "min"),
                RequestJson.position(object.get("max"), "max"),
                object.has("includeBlockStatePatterns")
                        ? RequestJson.stringList(
                                object.get("includeBlockStatePatterns"),
                                "includeBlockStatePatterns")
                        : List.of(),
                object.has("excludeBlockStatePatterns")
                        ? RequestJson.stringList(
                                object.get("excludeBlockStatePatterns"),
                                "excludeBlockStatePatterns")
                        : List.of(),
                object.has("includeAir")
                        ? RequestJson.bool(object.get("includeAir"), "includeAir")
                        : config.defaults().getBlocksIncludeAir(),
                maxResults);
    }
}
