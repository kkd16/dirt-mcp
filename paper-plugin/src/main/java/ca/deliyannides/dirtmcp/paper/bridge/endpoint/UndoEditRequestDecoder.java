package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdit;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class UndoEditRequestDecoder {
    private static final Set<String> FIELDS = Set.of("world", "editId");

    private UndoEditRequestDecoder() {}

    static UndoEdit.Request decode(BridgeExchange exchange)
            throws IOException, InvalidRequestException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireFields(object, FIELDS, FIELDS);
        String editId = RequestJson.string(object.get("editId"), "editId");
        try {
            UUID parsed = UUID.fromString(editId);
            if (parsed.version() != 4
                    || parsed.variant() != 2
                    || !parsed.toString().equals(editId.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("not a canonical UUID version 4");
            }
            return new UndoEdit.Request(RequestJson.string(object.get("world"), "world"), parsed);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException("editId must be a UUID version 4");
        }
    }
}
