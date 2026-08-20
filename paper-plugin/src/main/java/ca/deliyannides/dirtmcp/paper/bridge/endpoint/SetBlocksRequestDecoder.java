package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.world.edit.DestinationPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class SetBlocksRequestDecoder {
    private static final Set<String> REQUIRED_FIELDS =
            Set.of("world", "origin", "palettes", "placements");
    private static final Set<String> ALLOWED_FIELDS =
            Set.of("world", "origin", "palettes", "placements", "seed", "dryRun");

    private SetBlocksRequestDecoder() {}

    static SetBlocks.Request decode(BridgeExchange exchange, DirtConfig config)
            throws IOException, InvalidRequestException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireFields(object, REQUIRED_FIELDS, ALLOWED_FIELDS);
        return new SetBlocks.Request(
                RequestJson.string(object.get("world"), "world"),
                RequestJson.position(object.get("origin"), "origin"),
                palettes(object.get("palettes")),
                placements(object.get("placements")),
                object.has("seed")
                        ? RequestJson.integer(object.get("seed"), "seed")
                        : ThreadLocalRandom.current().nextInt(),
                object.has("dryRun")
                        ? RequestJson.bool(object.get("dryRun"), "dryRun")
                        : config.defaults().editDryRun());
    }

    private static List<List<DestinationPaletteEntry>> palettes(JsonElement element)
            throws InvalidRequestException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new InvalidRequestException("palettes must be a non-empty array");
        }
        List<List<DestinationPaletteEntry>> palettes =
                new ArrayList<>(element.getAsJsonArray().size());
        for (int index = 0; index < element.getAsJsonArray().size(); index++) {
            palettes.add(
                    DestinationPaletteDecoder.decode(
                            element.getAsJsonArray().get(index), "palettes[" + index + "]"));
        }
        return List.copyOf(palettes);
    }

    private static List<SetBlocks.Placement> placements(JsonElement element)
            throws InvalidRequestException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new InvalidRequestException("placements must be a non-empty array");
        }
        List<SetBlocks.Placement> placements = new ArrayList<>(element.getAsJsonArray().size());
        for (int index = 0; index < element.getAsJsonArray().size(); index++) {
            JsonElement entry = element.getAsJsonArray().get(index);
            String name = "placements[" + index + "]";
            if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 4) {
                throw new InvalidRequestException(
                        name + " must be a [paletteIndex, x, y, z] integer tuple");
            }
            JsonArray tuple = entry.getAsJsonArray();
            placements.add(
                    new SetBlocks.Placement(
                            RequestJson.integer(tuple.get(0), name + "[0]"),
                            RequestJson.integer(tuple.get(1), name + "[1]"),
                            RequestJson.integer(tuple.get(2), name + "[2]"),
                            RequestJson.integer(tuple.get(3), name + "[3]")));
        }
        return placements;
    }
}
