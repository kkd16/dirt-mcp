package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetBlocks;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

final class GetBlocksRequestDecoder {
    private static final Set<String> FIELDS =
            Set.of(
                    "world",
                    "min",
                    "max",
                    "includeBlockStatePatterns",
                    "excludeBlockStatePatterns",
                    "includeAir",
                    "maxResults");

    private GetBlocksRequestDecoder() {}

    static GetBlocks.Request decode(BridgeExchange exchange)
            throws IOException, OperationException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireExactFields(object, FIELDS, "Request");
        return new GetBlocks.Request(
                RequestJson.string(object.get("world"), "world"),
                RequestJson.position(object.get("min"), "min"),
                RequestJson.position(object.get("max"), "max"),
                RequestJson.stringList(
                        object.get("includeBlockStatePatterns"), "includeBlockStatePatterns"),
                RequestJson.stringList(
                        object.get("excludeBlockStatePatterns"), "excludeBlockStatePatterns"),
                RequestJson.bool(object.get("includeAir"), "includeAir"),
                RequestJson.integer(object.get("maxResults"), "maxResults"));
    }
}
