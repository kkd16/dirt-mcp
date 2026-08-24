package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.ReplaceRegionBlocks;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;

final class ReplaceRegionBlocksRequestDecoder {
    private static final Set<String> FIELDS =
            Set.of(
                    "world",
                    "min",
                    "max",
                    "sourceBlockStatePatterns",
                    "destinationPalette",
                    "seed",
                    "dryRun",
                    "label",
                    "maxChangedBlocks");

    private ReplaceRegionBlocksRequestDecoder() {}

    static ReplaceRegionBlocks.Request decode(BridgeExchange exchange)
            throws IOException, OperationException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireExactFields(object, FIELDS, "Request");
        return new ReplaceRegionBlocks.Request(
                RequestJson.string(object.get("world"), "world"),
                RequestJson.position(object.get("min"), "min"),
                RequestJson.position(object.get("max"), "max"),
                RequestJson.stringList(
                        object.get("sourceBlockStatePatterns"), "sourceBlockStatePatterns"),
                DestinationPaletteDecoder.decode(
                        object.get("destinationPalette"), "destinationPalette"),
                RequestJson.integer(object.get("seed"), "seed"),
                RequestJson.bool(object.get("dryRun"), "dryRun"),
                RequestJson.string(object.get("label"), "label"),
                RequestJson.nullableInteger(object.get("maxChangedBlocks"), "maxChangedBlocks"));
    }
}
