package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.world.edit.DestinationPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOperation;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOutcome;
import ca.deliyannides.dirtmcp.paper.world.edit.EditStatus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;
import java.time.Instant;

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
                            EditOperation.class,
                            (JsonSerializer<EditOperation>)
                                    (operation, ignoredType, ignoredContext) ->
                                            new com.google.gson.JsonPrimitive(operation.wireName()))
                    .registerTypeAdapter(
                            EditOutcome.class,
                            (JsonSerializer<EditOutcome>)
                                    (outcome, ignoredType, ignoredContext) ->
                                            new com.google.gson.JsonPrimitive(outcome.wireName()))
                    .registerTypeAdapter(
                            EditStatus.class,
                            (JsonSerializer<EditStatus>)
                                    (status, ignoredType, ignoredContext) ->
                                            new com.google.gson.JsonPrimitive(status.wireName()))
                    .registerTypeAdapter(
                            RunMinecraftCommands.Outcome.class,
                            (JsonSerializer<RunMinecraftCommands.Outcome>)
                                    (outcome, ignoredType, ignoredContext) ->
                                            new com.google.gson.JsonPrimitive(outcome.wireName()))
                    .registerTypeAdapter(
                            Instant.class,
                            (JsonSerializer<Instant>)
                                    (instant, ignoredType, ignoredContext) ->
                                            new com.google.gson.JsonPrimitive(instant.toString()))
                    .create();

    private BridgeJson() {}
}
