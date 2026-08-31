package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.DestinationPaletteEntry;
import com.google.gson.JsonArray;
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
        JsonArray array = RequestJson.array(element, field);
        List<DestinationPaletteEntry> entries = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement value = array.get(index);
            String entryField = field + "[" + index + "]";
            JsonObject object = RequestJson.object(value, entryField);
            RequestJson.requireFields(object, REQUIRED_FIELDS, ALLOWED_FIELDS, entryField);
            Integer weight = null;
            if (object.has("weight")) {
                weight = RequestJson.integer(object.get("weight"), entryField + ".weight");
            }
            entries.add(
                    new DestinationPaletteEntry(
                            RequestJson.string(
                                    object.get("blockState"), entryField + ".blockState"),
                            weight));
        }
        return entries;
    }
}
