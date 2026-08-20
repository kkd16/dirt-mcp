package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdit;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

final class UndoEditRequestDecoder {
    private static final Set<String> FIELDS = Set.of("world", "editId");

    private UndoEditRequestDecoder() {}

    static UndoEdit.Request decode(BridgeExchange exchange) throws IOException, OperationException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireExactFields(object, FIELDS, "Request");
        String editId = RequestJson.string(object.get("editId"), "editId");
        try {
            return new UndoEdit.Request(
                    RequestJson.string(object.get("world"), "world"),
                    UuidV4.parseCanonical(editId, "editId"));
        } catch (IllegalArgumentException exception) {
            throw RequestJson.invalid(exception.getMessage());
        }
    }
}
