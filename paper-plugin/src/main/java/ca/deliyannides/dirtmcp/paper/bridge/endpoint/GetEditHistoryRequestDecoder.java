package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.world.edit.GetEditHistory;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

final class GetEditHistoryRequestDecoder {
    private static final Set<String> FIELDS = Set.of("world");

    private GetEditHistoryRequestDecoder() {}

    static GetEditHistory.Request decode(BridgeExchange exchange)
            throws IOException, InvalidRequestException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireFields(object, FIELDS, FIELDS);
        return new GetEditHistory.Request(RequestJson.string(object.get("world"), "world"));
    }
}
