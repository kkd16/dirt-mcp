package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.world.edit.ReplaceRegionBlocks;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class ReplaceRegionBlocksRequestDecoder {
    private static final Set<String> REQUIRED_FIELDS =
            Set.of("world", "min", "max", "sourceBlockStatePatterns", "destinationPalette");
    private static final Set<String> ALLOWED_FIELDS =
            Set.of(
                    "world",
                    "min",
                    "max",
                    "sourceBlockStatePatterns",
                    "destinationPalette",
                    "seed",
                    "dryRun");

    private ReplaceRegionBlocksRequestDecoder() {}

    static ReplaceRegionBlocks.Request decode(BridgeExchange exchange, DirtConfig config)
            throws IOException, InvalidRequestException {
        JsonObject object = RequestJson.object(exchange);
        RequestJson.requireFields(object, REQUIRED_FIELDS, ALLOWED_FIELDS);
        return new ReplaceRegionBlocks.Request(
                RequestJson.string(object.get("world"), "world"),
                RequestJson.position(object.get("min"), "min"),
                RequestJson.position(object.get("max"), "max"),
                RequestJson.nonEmptyStringList(
                        object.get("sourceBlockStatePatterns"), "sourceBlockStatePatterns"),
                DestinationPaletteDecoder.decode(object.get("destinationPalette")),
                object.has("seed")
                        ? RequestJson.integer(object.get("seed"), "seed")
                        : ThreadLocalRandom.current().nextInt(),
                object.has("dryRun")
                        ? RequestJson.bool(object.get("dryRun"), "dryRun")
                        : config.defaults().editDryRun());
    }
}
