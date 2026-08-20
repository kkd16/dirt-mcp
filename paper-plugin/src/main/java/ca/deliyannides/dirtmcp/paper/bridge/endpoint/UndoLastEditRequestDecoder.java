package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoLastEdit;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

final class UndoLastEditRequestDecoder {
    private static final Set<String> FIELDS = Set.of("world");

    private UndoLastEditRequestDecoder() {}

    static UndoLastEdit.Request decode(BridgeExchange exchange)
            throws IOException, InvalidRequestException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireExactFields(object, FIELDS, "Request");
        return new UndoLastEdit.Request(RequestJson.string(object.get("world"), "world"));
    }
}
