package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.DestinationPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SetBlocksRequestDecoder {
    private static final Set<String> FIELDS =
            Set.of(
                    "world",
                    "origin",
                    "palettes",
                    "placements",
                    "runs",
                    "seed",
                    "dryRun",
                    "label",
                    "maxChangedBlocks");

    private SetBlocksRequestDecoder() {}

    static SetBlocks.Request decode(BridgeExchange exchange)
            throws IOException, OperationException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireExactFields(object, FIELDS, "Request");
        return new SetBlocks.Request(
                RequestJson.string(object.get("world"), "world"),
                RequestJson.position(object.get("origin"), "origin"),
                palettes(object.get("palettes")),
                placements(object.get("placements")),
                runs(object.get("runs")),
                RequestJson.integer(object.get("seed"), "seed"),
                RequestJson.bool(object.get("dryRun"), "dryRun"),
                RequestJson.string(object.get("label"), "label"),
                RequestJson.nullableInteger(object.get("maxChangedBlocks"), "maxChangedBlocks"));
    }

    private static List<List<DestinationPaletteEntry>> palettes(JsonElement element)
            throws OperationException {
        JsonArray array = RequestJson.array(element, "palettes");
        List<List<DestinationPaletteEntry>> palettes = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            palettes.add(
                    DestinationPaletteDecoder.decode(array.get(index), "palettes[" + index + "]"));
        }
        return palettes;
    }

    private static List<Placement> placements(JsonElement element) throws OperationException {
        JsonArray array = RequestJson.array(element, "placements");
        List<Placement> placements = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement entry = array.get(index);
            String name = "placements[" + index + "]";
            if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 4) {
                throw RequestJson.invalid(
                        name + " must be a [paletteIndex, x, y, z] integer tuple",
                        new ErrorDetails.InvalidRequest.InvalidValue(name));
            }
            JsonArray tuple = entry.getAsJsonArray();
            placements.add(
                    new Placement(
                            RequestJson.integer(tuple.get(0), name + "[0]"),
                            RequestJson.integer(tuple.get(1), name + "[1]"),
                            RequestJson.integer(tuple.get(2), name + "[2]"),
                            RequestJson.integer(tuple.get(3), name + "[3]")));
        }
        return placements;
    }

    private static List<Run> runs(JsonElement element) throws OperationException {
        JsonArray array = RequestJson.array(element, "runs");
        List<Run> runs = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement entry = array.get(index);
            String name = "runs[" + index + "]";
            if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 7) {
                throw RequestJson.invalid(
                        name + " must be a [paletteIndex, x, y, z, toX, toY, toZ] integer tuple",
                        new ErrorDetails.InvalidRequest.InvalidValue(name));
            }
            JsonArray tuple = entry.getAsJsonArray();
            runs.add(
                    new Run(
                            RequestJson.integer(tuple.get(0), name + "[0]"),
                            RequestJson.integer(tuple.get(1), name + "[1]"),
                            RequestJson.integer(tuple.get(2), name + "[2]"),
                            RequestJson.integer(tuple.get(3), name + "[3]"),
                            RequestJson.integer(tuple.get(4), name + "[4]"),
                            RequestJson.integer(tuple.get(5), name + "[5]"),
                            RequestJson.integer(tuple.get(6), name + "[6]")));
        }
        return runs;
    }
}
