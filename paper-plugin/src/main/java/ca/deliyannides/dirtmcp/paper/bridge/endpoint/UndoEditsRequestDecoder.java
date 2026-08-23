package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdits;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class UndoEditsRequestDecoder {
    private static final Set<String> FIELDS = Set.of("world", "editIds");

    private UndoEditsRequestDecoder() {}

    static UndoEdits.Request decode(BridgeExchange exchange)
            throws IOException, OperationException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireExactFields(object, FIELDS, "Request");
        List<String> values = RequestJson.stringList(object.get("editIds"), "editIds");
        List<UUID> editIds = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            try {
                editIds.add(UuidV4.parseCanonical(values.get(index), "editIds[" + index + "]"));
            } catch (IllegalArgumentException exception) {
                throw RequestJson.invalid(
                        exception.getMessage(),
                        new ErrorDetails.InvalidRequest.InvalidValue("editIds[" + index + "]"));
            }
        }
        return new UndoEdits.Request(RequestJson.string(object.get("world"), "world"), editIds);
    }
}
