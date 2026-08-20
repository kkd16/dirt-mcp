package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.DestinationPaletteEntry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class DestinationPaletteDecoder {
    private static final Set<String> REQUIRED_FIELDS = Set.of("blockState");
    private static final Set<String> ALLOWED_FIELDS = Set.of("blockState", "weight");

    private DestinationPaletteDecoder() {}

    static List<DestinationPaletteEntry> decode(JsonElement element, String field)
            throws OperationException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw RequestJson.invalid(
                    field + " must be a non-empty array",
                    new ErrorDetails.InvalidRequest.InvalidValue(field));
        }
        List<DestinationPaletteEntry> entries = new ArrayList<>(element.getAsJsonArray().size());
        for (int index = 0; index < element.getAsJsonArray().size(); index++) {
            JsonElement value = element.getAsJsonArray().get(index);
            if (!value.isJsonObject()) {
                String entryField = field + "[" + index + "]";
                throw RequestJson.invalid(
                        entryField + " must be an object",
                        new ErrorDetails.InvalidRequest.InvalidValue(entryField));
            }
            JsonObject object = value.getAsJsonObject();
            RequestJson.requireFields(
                    object, REQUIRED_FIELDS, ALLOWED_FIELDS, field + "[" + index + "]");
            Integer weight = null;
            if (object.has("weight")) {
                weight =
                        RequestJson.integer(object.get("weight"), field + "[" + index + "].weight");
            }
            entries.add(
                    new DestinationPaletteEntry(
                            RequestJson.string(
                                    object.get("blockState"), field + "[" + index + "].blockState"),
                            weight));
        }
        return List.copyOf(entries);
    }
}
