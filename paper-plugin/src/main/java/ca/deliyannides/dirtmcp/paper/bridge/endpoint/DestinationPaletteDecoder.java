package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
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

    static List<DestinationPaletteEntry> decode(JsonElement element)
            throws InvalidRequestException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new InvalidRequestException("destinationPalette must be a non-empty array");
        }
        List<DestinationPaletteEntry> entries = new ArrayList<>(element.getAsJsonArray().size());
        for (int index = 0; index < element.getAsJsonArray().size(); index++) {
            JsonElement value = element.getAsJsonArray().get(index);
            if (!value.isJsonObject()) {
                throw new InvalidRequestException(
                        "destinationPalette[" + index + "] must be an object");
            }
            JsonObject object = value.getAsJsonObject();
            RequestJson.requireFields(object, REQUIRED_FIELDS, ALLOWED_FIELDS);
            Integer weight = null;
            if (object.has("weight")) {
                weight =
                        RequestJson.integer(
                                object.get("weight"), "destinationPalette[" + index + "].weight");
            }
            entries.add(
                    new DestinationPaletteEntry(
                            RequestJson.string(
                                    object.get("blockState"),
                                    "destinationPalette[" + index + "].blockState"),
                            weight));
        }
        return List.copyOf(entries);
    }
}
