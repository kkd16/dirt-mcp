package ca.deliyannides.dirtmcp.paper.bridge.endpoint;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeExchange;
import ca.deliyannides.dirtmcp.paper.bridge.InvalidRequestException;
import ca.deliyannides.dirtmcp.paper.bridge.RequestJson;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.world.edit.BlockChange;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SetBlocksRequestDecoder {
    private static final Set<String> REQUIRED_FIELDS = Set.of("world", "changes");
    private static final Set<String> ALLOWED_FIELDS = Set.of("world", "changes", "dryRun");
    private static final Set<String> CHANGE_FIELDS = Set.of("position", "blockState");

    private SetBlocksRequestDecoder() {}

    static SetBlocks.Request decode(BridgeExchange exchange, DirtConfig config)
            throws IOException, InvalidRequestException {
        JsonObject object = exchange.readJsonObject();
        RequestJson.requireFields(object, REQUIRED_FIELDS, ALLOWED_FIELDS);
        return new SetBlocks.Request(
                RequestJson.string(object.get("world"), "world"),
                changes(object.get("changes")),
                object.has("dryRun")
                        ? RequestJson.bool(object.get("dryRun"), "dryRun")
                        : config.defaults().editDryRun());
    }

    private static List<BlockChange> changes(JsonElement element) throws InvalidRequestException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new InvalidRequestException("changes must be a non-empty array");
        }
        List<BlockChange> changes = new ArrayList<>(element.getAsJsonArray().size());
        for (int index = 0; index < element.getAsJsonArray().size(); index++) {
            JsonElement entry = element.getAsJsonArray().get(index);
            if (!entry.isJsonObject()) {
                throw new InvalidRequestException("changes[" + index + "] must be an object");
            }
            JsonObject object = entry.getAsJsonObject();
            RequestJson.requireExactFields(object, CHANGE_FIELDS, "changes[" + index + "]");
            changes.add(
                    new BlockChange(
                            RequestJson.position(
                                    object.get("position"), "changes[" + index + "].position"),
                            RequestJson.string(
                                    object.get("blockState"),
                                    "changes[" + index + "].blockState")));
        }
        return List.copyOf(changes);
    }
}
