package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.world.edit.DestinationPaletteEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;

final class BridgeJson {
    static final Gson GSON =
            new GsonBuilder()
                    .serializeNulls()
                    .registerTypeAdapter(
                            DestinationPaletteEntry.class,
                            (JsonSerializer<DestinationPaletteEntry>)
                                    (entry, ignoredType, ignoredContext) -> {
                                        JsonObject object = new JsonObject();
                                        object.addProperty("blockState", entry.blockState());
                                        if (entry.weight() != null) {
                                            object.addProperty("weight", entry.weight());
                                        }
                                        return object;
                                    })
                    .registerTypeAdapter(
                            RunMinecraftCommands.Outcome.class,
                            (JsonSerializer<RunMinecraftCommands.Outcome>)
                                    (outcome, ignoredType, ignoredContext) ->
                                            new com.google.gson.JsonPrimitive(outcome.wireName()))
                    .create();

    private BridgeJson() {}
}
