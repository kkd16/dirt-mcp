package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SetBlocksRequestDecoder {
    private static final Set<String> REQUIRED_FIELDS =
            Set.of("world", "origin", "palette", "placements");
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("world", "origin", "palette", "placements", "dryRun");
    private static final Set<String> PLACEMENT_FIELDS = Set.of("paletteIndex", "offsets");

    private SetBlocksRequestDecoder() {}

    static SetBlocks.Request decode(BridgeExchange exchange, DirtConfig config)
            throws IOException, InvalidRequestException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireFields(object, REQUIRED_FIELDS, ALLOWED_FIELDS);
        return new SetBlocks.Request(
                RequestJson.string(object.get("world"), "world"),
                RequestJson.position(object.get("origin"), "origin"),
                RequestJson.nonEmptyStringList(object.get("palette"), "palette"),
                placements(object.get("placements")),
                object.has("dryRun")
                        ? RequestJson.bool(object.get("dryRun"), "dryRun")
                        : config.defaults().editDryRun());
    }

    private static List<SetBlocks.Placement> placements(JsonElement element)
            throws InvalidRequestException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new InvalidRequestException("placements must be a non-empty array");
        }
        List<SetBlocks.Placement> placements = new ArrayList<>(element.getAsJsonArray().size());
        for (int index = 0; index < element.getAsJsonArray().size(); index++) {
            JsonElement entry = element.getAsJsonArray().get(index);
            if (!entry.isJsonObject()) {
                throw new InvalidRequestException("placements[" + index + "] must be an object");
            }
            JsonObject object = entry.getAsJsonObject();
            String name = "placements[" + index + "]";
            RequestJson.requireExactFields(object, PLACEMENT_FIELDS, name);
            placements.add(
                    new SetBlocks.Placement(
                            RequestJson.integer(object.get("paletteIndex"), name + ".paletteIndex"),
                            offsets(object.get("offsets"), name + ".offsets")));
        }
        return List.copyOf(placements);
    }

    private static List<SetBlocks.Offset> offsets(JsonElement element, String name)
            throws InvalidRequestException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new InvalidRequestException(name + " must be a non-empty array");
        }
        JsonArray array = element.getAsJsonArray();
        List<SetBlocks.Offset> offsets = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement entry = array.get(index);
            String offsetName = name + "[" + index + "]";
            if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 3) {
                throw new InvalidRequestException(
                        offsetName + " must be an [x, y, z] integer tuple");
            }
            JsonArray tuple = entry.getAsJsonArray();
            offsets.add(
                    new SetBlocks.Offset(
                            RequestJson.integer(tuple.get(0), offsetName + "[0]"),
                            RequestJson.integer(tuple.get(1), offsetName + "[1]"),
                            RequestJson.integer(tuple.get(2), offsetName + "[2]")));
        }
        return List.copyOf(offsets);
    }
}
