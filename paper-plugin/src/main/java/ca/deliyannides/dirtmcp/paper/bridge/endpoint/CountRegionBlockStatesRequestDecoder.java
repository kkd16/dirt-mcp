package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.world.inspection.CountRegionBlockStates;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

final class CountRegionBlockStatesRequestDecoder {
    private static final Set<String> FIELDS = Set.of("world", "min", "max");

    private CountRegionBlockStatesRequestDecoder() {}

    static CountRegionBlockStates.Request decode(BridgeExchange exchange)
            throws IOException, InvalidRequestException {
        JsonObject object = RequestJson.object(exchange);
        RequestJson.requireExactFields(object, FIELDS, "Request");
        return new CountRegionBlockStates.Request(
                RequestJson.string(object.get("world"), "world"),
                RequestJson.position(object.get("min"), "min"),
                RequestJson.position(object.get("max"), "max"));
    }
}
