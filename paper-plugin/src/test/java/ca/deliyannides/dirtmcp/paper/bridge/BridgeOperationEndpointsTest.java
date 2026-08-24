package ca.deliyannides.dirtmcp.paper.bridge;

import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.authorized;
import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.availablePort;
import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.config;
import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.json;
import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.post;
import static ca.deliyannides.dirtmcp.paper.bridge.BridgeTestFixture.server;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.config.McpTool;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.world.edit.DestinationPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOperation;
import ca.deliyannides.dirtmcp.paper.world.edit.EditRecord;
import ca.deliyannides.dirtmcp.paper.world.edit.EditStatus;
import ca.deliyannides.dirtmcp.paper.world.edit.GetEditHistory;
import ca.deliyannides.dirtmcp.paper.world.edit.ReplaceRegionBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdits;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEditsException;
import ca.deliyannides.dirtmcp.paper.world.inspection.CountRegionBlockStates;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetBlocks;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetBlocks.ExactPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Placement;
import ca.deliyannides.dirtmcp.paper.world.model.BlockStructure.Run;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BridgeOperationEndpointsTest {
    @Test
    void returnsOnlinePlayerFacingInServerStatus() throws Exception {
        try (BridgeServer bridge =
                        server(config(availablePort(), 4), new BridgeTestFixture.TestOperations());
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> response =
                    send(client, authorized(bridge, "/v1/server-status").GET().build());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "north",
                    json(response.body())
                            .getAsJsonObject()
                            .getAsJsonObject("players")
                            .getAsJsonArray("entries")
                            .get(0)
                            .getAsJsonObject()
                            .get("facing")
                            .getAsString());
            var tools = json(response.body()).getAsJsonObject().getAsJsonObject("tools");
            assertEquals(McpTool.values().length, tools.size());
            for (McpTool tool : McpTool.values()) {
                assertTrue(tools.get(tool.id()).getAsBoolean());
            }
            var logging = json(response.body()).getAsJsonObject().getAsJsonObject("logging");
            assertEquals("info", logging.get("consoleLevel").getAsString());
            assertEquals(10_485_760, logging.get("detailFileMaxBytes").getAsInt());
            assertEquals(5, logging.get("detailFileRetainedFiles").getAsInt());
            var limits = json(response.body()).getAsJsonObject().getAsJsonObject("limits");
            assertEquals(10, limits.get("maxCommandsPerRequest").getAsInt());
            assertEquals(8_192, limits.get("maxCommandFeedbackCharacters").getAsInt());
        }
    }

    @Test
    void parsesCommandBatchesAndSerializesLowerCasePerCommandOutcomes() throws Exception {
        AtomicReference<RunMinecraftCommands.Request> captured = new AtomicReference<>();
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public RunMinecraftCommands.Result runCommands(
                            RunMinecraftCommands.Request request) {
                        captured.set(request);
                        return new RunMinecraftCommands.Result(
                                new RunMinecraftCommands.Sender("DirtMCP", true, false),
                                true,
                                List.of(
                                        new RunMinecraftCommands.CommandResult(
                                                "say hello",
                                                RunMinecraftCommands.Outcome.DISPATCHED,
                                                List.of("hello"),
                                                null,
                                                null),
                                        new RunMinecraftCommands.CommandResult(
                                                "missing",
                                                RunMinecraftCommands.Outcome.NOT_FOUND,
                                                List.of(),
                                                "Paper found no target for this command",
                                                null)));
                    }
                };
        try (BridgeServer bridge = server(config(availablePort(), 4), operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> response =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/run-minecraft-commands",
                                    "{\"commands\":[\"say hello\",\"missing\",\"must not run\"]}"));

            assertEquals(200, response.statusCode());
            assertEquals(
                    new RunMinecraftCommands.Request(
                            List.of("say hello", "missing", "must not run")),
                    captured.get());
            assertEquals(
                    json(
                            """
                            {
                              "sender":{"name":"DirtMCP","isOperator":true,"isPlayer":false},
                              "feedbackTruncated":true,
                              "results":[
                                {"command":"say hello","outcome":"dispatched","feedback":["hello"],
                                 "message":null,"rawMessage":null},
                                {"command":"missing","outcome":"not_found","feedback":[],
                                 "message":"Paper found no target for this command","rawMessage":null}
                              ]
                            }
                            """),
                    json(response.body()));
        }
    }

    @Test
    void parsesAndDispatchesInspectionOperations() throws Exception {
        AtomicReference<CountRegionBlockStates.Request> countRequest = new AtomicReference<>();
        AtomicReference<GetBlocks.Request> blocksRequest = new AtomicReference<>();
        AtomicReference<ScanOrthographicView.Request> viewRequest = new AtomicReference<>();
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public CountRegionBlockStates.Result countRegionBlockStates(
                            CountRegionBlockStates.Request request) throws OperationException {
                        countRequest.set(request);
                        return super.countRegionBlockStates(request);
                    }

                    @Override
                    public GetBlocks.Result getBlocks(GetBlocks.Request request)
                            throws OperationException {
                        blocksRequest.set(request);
                        return new GetBlocks.Result(
                                request.world(),
                                request.min(),
                                List.of(
                                        List.of(new ExactPaletteEntry("minecraft:stone")),
                                        List.of(new ExactPaletteEntry("minecraft:dirt"))),
                                List.of(new Placement(0, 0, 0, 0)),
                                List.of(new Run(1, 1, 0, 0, 1, 0, 0)));
                    }

                    @Override
                    public ScanOrthographicView.Result scanOrthographicView(
                            ScanOrthographicView.Request request) throws OperationException {
                        viewRequest.set(request);
                        return super.scanOrthographicView(request);
                    }
                };
        try (BridgeServer bridge = server(config(availablePort(), 4), operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> count =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/count-region-block-states",
                                    """
                                    {"world":"world","min":{"x":5,"y":60,"z":-2},
                                     "max":{"x":6,"y":61,"z":-1}}
                                    """));
            HttpResponse<String> blocks =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/get-blocks",
                                    """
                                    {"world":"world","min":{"x":1,"y":2,"z":3},
                                     "max":{"x":2,"y":2,"z":3},
                                     "includeBlockStatePatterns":["minecraft:stone"],
                                     "excludeBlockStatePatterns":["minecraft:air"],
                                     "includeAir":true,"maxResults":25}
                                    """));
            HttpResponse<String> view =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/scan-orthographic-view",
                                    """
                                    {"world":"world","origin":{"x":8,"y":70,"z":9},
                                     "direction":"west","horizontalRadius":4,"verticalRadius":3,
                                     "maxDistance":12,"depth":2,"maxResults":40}
                                    """));

            assertEquals(200, count.statusCode());
            assertEquals(
                    json(
                            """
                            {"world":"world","bounds":{"min":{"x":5,"y":60,"z":-2},
                             "max":{"x":6,"y":61,"z":-1}},"dimensions":{"x":1,"y":1,"z":1},
                             "volume":1,"blockStateCounts":{"minecraft:stone":1}}
                            """),
                    json(count.body()));
            assertEquals(
                    new CountRegionBlockStates.Request(
                            "world", new BlockPosition(5, 60, -2), new BlockPosition(6, 61, -1)),
                    countRequest.get());
            assertEquals(
                    List.of("minecraft:stone"), blocksRequest.get().includeBlockStatePatterns());
            assertEquals(List.of("minecraft:air"), blocksRequest.get().excludeBlockStatePatterns());
            assertTrue(blocksRequest.get().includeAir());
            assertEquals(25, blocksRequest.get().maxResults());
            assertEquals(200, blocks.statusCode());
            assertEquals(
                    json(
                            """
                            {"world":"world","origin":{"x":1,"y":2,"z":3},
                             "palettes":[[{"blockState":"minecraft:stone"}],
                                         [{"blockState":"minecraft:dirt"}]],
                             "placements":[[0,0,0,0]],
                             "runs":[[1,1,0,0,1,0,0]]}
                            """),
                    json(blocks.body()));
            assertEquals(ScanOrthographicView.Direction.WEST, viewRequest.get().direction());
            assertEquals(4, viewRequest.get().horizontalRadius());
            assertEquals(3, viewRequest.get().verticalRadius());
            assertEquals(12, viewRequest.get().maxDistance());
            assertEquals(2, viewRequest.get().depth());
            assertEquals(40, viewRequest.get().maxResults());
            assertEquals(200, view.statusCode());
        }
    }

    @Test
    void parsesPlayerContextDefaultsAndGranularIncludes() throws Exception {
        AtomicReference<GetPlayerContext.Request> playerRequest = new AtomicReference<>();
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public GetPlayerContext.Result getPlayerContext(
                            GetPlayerContext.Request request) throws OperationException {
                        playerRequest.set(request);
                        return super.getPlayerContext(request);
                    }
                };
        try (BridgeServer bridge = server(config(availablePort(), 4), operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> defaults =
                    send(
                            client,
                            post(bridge, "/v1/get-player-context", "{\"player\":\"Builder\"}"));

            assertEquals(200, defaults.statusCode());
            assertEquals(
                    new GetPlayerContext.Includes(true, false, false, false, false, false, false),
                    playerRequest.get().include());
            var defaultBody = json(defaults.body()).getAsJsonObject();
            assertFalse(defaultBody.has("included"));
            assertFalse(defaultBody.has("view"));
            assertTrue(defaultBody.has("equipment"));
            assertTrue(defaultBody.get("inventory").isJsonNull());

            HttpResponse<String> granular =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/get-player-context",
                                    """
                                    {"player":"Builder","include":{"equipment":false,
                                     "inventory":true,"enderChest":true,
                                     "vitals":true,"movement":true,"client":true,"effects":true}}
                                    """));

            assertEquals(200, granular.statusCode());
            assertEquals(
                    new GetPlayerContext.Includes(false, true, true, true, true, true, true),
                    playerRequest.get().include());
            var granularBody = json(granular.body()).getAsJsonObject();
            assertFalse(granularBody.has("view"));
            assertEquals(36, granularBody.getAsJsonObject("inventory").get("size").getAsInt());
            assertEquals(27, granularBody.getAsJsonObject("enderChest").get("size").getAsInt());
            assertEquals(
                    40,
                    granularBody
                            .getAsJsonObject("vitals")
                            .get("calculatedExperiencePoints")
                            .getAsInt());
            assertEquals(
                    "en-US", granularBody.getAsJsonObject("client").get("locale").getAsString());

            HttpResponse<String> malformedSelector =
                    send(client, post(bridge, "/v1/get-player-context", "{\"player\":\" \"}"));
            assertEquals(400, malformedSelector.statusCode());
            assertEquals(
                    "player",
                    json(malformedSelector.body())
                            .getAsJsonObject()
                            .getAsJsonObject("error")
                            .getAsJsonObject("details")
                            .get("field")
                            .getAsString());

            HttpResponse<String> oversizedSelector =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/get-player-context",
                                    "{\"player\":\"" + "x".repeat(37) + "\"}"));
            assertEquals(400, oversizedSelector.statusCode());
            var oversizedDetails =
                    json(oversizedSelector.body())
                            .getAsJsonObject()
                            .getAsJsonObject("error")
                            .getAsJsonObject("details");
            assertEquals("out_of_range", oversizedDetails.get("reason").getAsString());
            assertEquals("player.length", oversizedDetails.get("target").getAsString());
            assertEquals(37, oversizedDetails.get("value").getAsInt());
            assertEquals(36, oversizedDetails.get("maximum").getAsInt());

            HttpResponse<String> removedViewInput =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/get-player-context",
                                    "{\"player\":\"Builder\",\"include\":{\"view\":false},\"view\":{}}"));
            assertEquals(400, removedViewInput.statusCode());
        }
    }

    @Test
    void parsesPlayerAndSyntheticPerspectiveSources() throws Exception {
        AtomicReference<GetPerspectiveView.Request> perspectiveRequest = new AtomicReference<>();
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public GetPerspectiveView.Result getPerspectiveView(
                            GetPerspectiveView.Request request) throws OperationException {
                        perspectiveRequest.set(request);
                        return super.getPerspectiveView(request);
                    }
                };
        try (BridgeServer bridge = server(config(availablePort(), 4), operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> player =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/get-perspective-view",
                                    "{\"source\":{\"type\":\"player\",\"player\":\"builder\"}}"));

            assertEquals(200, player.statusCode());
            assertEquals(
                    new GetPerspectiveView.PlayerSource("builder"),
                    perspectiveRequest.get().source());
            assertEquals(21, perspectiveRequest.get().options().width());
            assertEquals(13, perspectiveRequest.get().options().height());
            assertEquals(
                    GetPerspectiveView.FluidCollision.NEVER,
                    perspectiveRequest.get().options().fluidCollision());
            var playerBody = json(player.body()).getAsJsonObject();
            assertEquals("player", playerBody.getAsJsonObject("source").get("type").getAsString());
            assertEquals(
                    "Builder",
                    playerBody
                            .getAsJsonObject("source")
                            .getAsJsonObject("player")
                            .get("name")
                            .getAsString());
            assertEquals(1, playerBody.get("checkedChunkCount").getAsInt());

            HttpResponse<String> location =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/get-perspective-view",
                                    """
                                    {"source":{"type":"location","world":"world",
                                     "cameraPosition":{"x":1.25,"y":72,"z":-8.5},
                                     "rotation":{"yaw":45,"pitch":-15}},
                                     "width":5,"height":3,"maxDistance":12,
                                     "fluidCollision":"always","ignorePassableBlocks":true}
                                    """));

            assertEquals(200, location.statusCode());
            var source = (GetPerspectiveView.LocationSource) perspectiveRequest.get().source();
            assertEquals("world", source.world());
            assertEquals(1.25, source.cameraPosition().x());
            assertEquals(45, source.rotation().yaw());
            assertEquals(5, perspectiveRequest.get().options().width());
            assertEquals(
                    GetPerspectiveView.FluidCollision.ALWAYS,
                    perspectiveRequest.get().options().fluidCollision());
            var locationBody = json(location.body()).getAsJsonObject();
            assertEquals(
                    "location", locationBody.getAsJsonObject("source").get("type").getAsString());
            assertEquals(
                    1.25, locationBody.getAsJsonObject("cameraPosition").get("x").getAsDouble());
        }
    }

    @Test
    void parsesAndDispatchesEditingOperations() throws Exception {
        AtomicReference<ReplaceRegionBlocks.Request> replaceRequest = new AtomicReference<>();
        AtomicReference<SetBlocks.Request> setRequest = new AtomicReference<>();
        AtomicReference<GetEditHistory.Request> historyRequest = new AtomicReference<>();
        AtomicReference<UndoEdits.Request> undoRequest = new AtomicReference<>();
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public ReplaceRegionBlocks.Result replaceRegionBlocks(
                            ReplaceRegionBlocks.Request request, UUID callId)
                            throws OperationException {
                        replaceRequest.set(request);
                        return super.replaceRegionBlocks(request, callId);
                    }

                    @Override
                    public SetBlocks.Result setBlocks(SetBlocks.Request request, UUID callId)
                            throws OperationException {
                        setRequest.set(request);
                        return super.setBlocks(request, callId);
                    }

                    @Override
                    public GetEditHistory.Result getEditHistory(GetEditHistory.Request request)
                            throws OperationException {
                        historyRequest.set(request);
                        return super.getEditHistory(request);
                    }

                    @Override
                    public UndoEdits.Result undoEdits(UndoEdits.Request request, UUID callId)
                            throws OperationException {
                        undoRequest.set(request);
                        return super.undoEdits(request, callId);
                    }
                };
        try (BridgeServer bridge = server(config(availablePort(), 4), operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> replace =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/replace-region-blocks",
                                    """
                                    {"world":"world","min":{"x":0,"y":1,"z":2},
                                     "max":{"x":3,"y":4,"z":5},
                                     "sourceBlockStatePatterns":["minecraft:stone"],
                                     "destinationPalette":[{"blockState":"minecraft:dirt","weight":40},
                                     {"blockState":"minecraft:grass_block","weight":60}],
                                     "seed":17,"dryRun":true,"label":"Preview stone replacement",
                                     "maxChangedBlocks":19}
                                    """));
            HttpResponse<String> set =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/set-blocks",
                                    """
                                    {"world":"world","origin":{"x":7,"y":8,"z":9},
                                     "palettes":[[{"blockState":"minecraft:gold_block","weight":25},
                                     {"blockState":"minecraft:iron_block","weight":75}]],
                                     "placements":[[0,0,0,0]],
                                     "runs":[[0,1,0,0,1,0,0]],"seed":23,"dryRun":true,
                                     "label":"Preview metal blocks","maxChangedBlocks":11}
                                    """));
            HttpResponse<String> history =
                    send(client, post(bridge, "/v1/get-edit-history", "{\"world\":\"world\"}"));
            HttpResponse<String> undo =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edits",
                                    "{\"world\":\"world\",\"editIds\":[\""
                                            + BridgeTestFixture.EDIT_ID
                                            + "\"]}"));
            HttpResponse<String> legacyUndo =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edit",
                                    "{\"world\":\"world\",\"editId\":\""
                                            + BridgeTestFixture.EDIT_ID
                                            + "\"}"));
            assertEquals(200, replace.statusCode());
            assertEquals(17, replaceRequest.get().seed());
            assertTrue(replaceRequest.get().dryRun());
            assertEquals("Preview stone replacement", replaceRequest.get().label());
            assertEquals(19, replaceRequest.get().maxChangedBlocks());
            assertEquals(40, replaceRequest.get().destinationPalette().getFirst().weight());
            assertEquals(200, set.statusCode());
            assertEquals(new BlockPosition(7, 8, 9), setRequest.get().origin());
            assertEquals(23, setRequest.get().seed());
            assertEquals("Preview metal blocks", setRequest.get().label());
            assertEquals(11, setRequest.get().maxChangedBlocks());
            assertEquals(
                    List.of(
                            new DestinationPaletteEntry("minecraft:gold_block", 25),
                            new DestinationPaletteEntry("minecraft:iron_block", 75)),
                    setRequest.get().palettes().getFirst());
            assertEquals(0, setRequest.get().placements().getFirst().paletteIndex());
            assertEquals(new Placement(0, 0, 0, 0), setRequest.get().placements().getFirst());
            assertEquals(new Run(0, 1, 0, 0, 1, 0, 0), setRequest.get().runs().getFirst());
            assertEquals(new GetEditHistory.Request("world"), historyRequest.get());
            assertEquals(200, history.statusCode());
            assertEquals(
                    new UndoEdits.Request("world", List.of(BridgeTestFixture.EDIT_ID)),
                    undoRequest.get());
            assertEquals(200, undo.statusCode());
            assertEquals(404, legacyUndo.statusCode());
        }
    }

    @Test
    void serializesCompleteHistoryAndUndoResponses() throws Exception {
        UUID recoveryEditId = UUID.fromString("423e4567-e89b-42d3-a456-426614174000");
        EditRecord recovery =
                new EditRecord(
                        recoveryEditId,
                        UUID.fromString("523e4567-e89b-42d3-a456-426614174000"),
                        EditOperation.SET_BLOCKS,
                        "Repair the west wall",
                        "world",
                        BridgeTestFixture.WORLD_ID,
                        new BlockBounds(new BlockPosition(-2, 64, 8), new BlockPosition(3, 70, 12)),
                        7,
                        Instant.parse("2026-08-19T12:02:03.120000000Z"),
                        EditStatus.RECOVERY_REQUIRED);
        EditRecord committed =
                new EditRecord(
                        BridgeTestFixture.EDIT_ID,
                        UUID.fromString("623e4567-e89b-42d3-a456-426614174000"),
                        EditOperation.REPLACE_REGION_BLOCKS,
                        "Replace the courtyard floor",
                        "world",
                        BridgeTestFixture.WORLD_ID,
                        new BlockBounds(new BlockPosition(0, 60, 0), new BlockPosition(1, 61, 1)),
                        3,
                        Instant.parse("2026-08-19T12:00:00.000Z"),
                        EditStatus.COMMITTED);
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public GetEditHistory.Result getEditHistory(GetEditHistory.Request request) {
                        return new GetEditHistory.Result(
                                request.world(), List.of(recovery, committed));
                    }

                    @Override
                    public UndoEdits.Result undoEdits(UndoEdits.Request request, UUID callId) {
                        return new UndoEdits.Result(
                                request.world(),
                                List.of(recovery),
                                callId,
                                Instant.parse("2026-08-19T12:05:00.000000Z"));
                    }
                };
        try (BridgeServer bridge = server(config(availablePort(), 4), operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> history =
                    send(client, post(bridge, "/v1/get-edit-history", "{\"world\":\"world\"}"));
            HttpResponse<String> undo =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edits",
                                    "{\"world\":\"world\",\"editIds\":[\""
                                            + recoveryEditId
                                            + "\"]}"));

            assertEquals(200, history.statusCode());
            assertEquals(
                    json(
                            """
                            {
                              "world":"world",
                              "edits":[
                                {
                                  "editId":"423e4567-e89b-42d3-a456-426614174000",
                                  "callId":"523e4567-e89b-42d3-a456-426614174000",
                                  "operation":"set_blocks",
                                  "label":"Repair the west wall",
                                  "world":"world",
                                  "worldId":"323e4567-e89b-42d3-a456-426614174000",
                                  "bounds":{"min":{"x":-2,"y":64,"z":8},
                                            "max":{"x":3,"y":70,"z":12}},
                                  "changedBlockCount":7,
                                  "completedAt":"2026-08-19T12:02:03.120Z",
                                  "status":"recovery_required"
                                },
                                {
                                  "editId":"223e4567-e89b-42d3-a456-426614174000",
                                  "callId":"623e4567-e89b-42d3-a456-426614174000",
                                  "operation":"replace_region_blocks",
                                  "label":"Replace the courtyard floor",
                                  "world":"world",
                                  "worldId":"323e4567-e89b-42d3-a456-426614174000",
                                  "bounds":{"min":{"x":0,"y":60,"z":0},
                                            "max":{"x":1,"y":61,"z":1}},
                                  "changedBlockCount":3,
                                  "completedAt":"2026-08-19T12:00:00Z",
                                  "status":"committed"
                                }
                              ]
                            }
                            """),
                    json(history.body()));
            assertEquals(200, undo.statusCode());
            assertEquals(
                    json(
                            """
                            {
                              "world":"world",
                              "edits":[{
                                "editId":"423e4567-e89b-42d3-a456-426614174000",
                                "callId":"523e4567-e89b-42d3-a456-426614174000",
                                "operation":"set_blocks",
                                "label":"Repair the west wall",
                                "world":"world",
                                "worldId":"323e4567-e89b-42d3-a456-426614174000",
                                "bounds":{"min":{"x":-2,"y":64,"z":8},
                                          "max":{"x":3,"y":70,"z":12}},
                                "changedBlockCount":7,
                                "completedAt":"2026-08-19T12:02:03.120Z",
                                "status":"recovery_required"
                              }],
                              "undoCallId":"123e4567-e89b-42d3-a456-426614174000",
                              "undoneAt":"2026-08-19T12:05:00Z"
                            }
                            """),
                    json(undo.body()));
        }
    }

    @Test
    void serializesUndoExecutionProgressBesideTheError() throws Exception {
        UUID undoneId = UUID.fromString("423e4567-e89b-42d3-a456-426614174000");
        UUID failedId = UUID.fromString("523e4567-e89b-42d3-a456-426614174000");
        EditRecord undone =
                new EditRecord(
                        undoneId,
                        UUID.fromString("623e4567-e89b-42d3-a456-426614174000"),
                        EditOperation.SET_BLOCKS,
                        "Undo completed prefix",
                        "world",
                        BridgeTestFixture.WORLD_ID,
                        new BlockBounds(new BlockPosition(0, 64, 0), new BlockPosition(0, 64, 0)),
                        1,
                        Instant.parse("2026-08-19T12:00:00Z"),
                        EditStatus.COMMITTED);
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public UndoEdits.Result undoEdits(UndoEdits.Request request, UUID callId)
                            throws OperationException {
                        List<EditRecord> progress =
                                request.editIds().size() == 1 ? List.of() : List.of(undone);
                        UUID failed = request.editIds().get(progress.size());
                        throw new UndoEditsException(
                                OperationFailure.WORLD_UNAVAILABLE,
                                "Undo execution failed",
                                new ErrorDetails.WorldUnavailable.OperationFailed(),
                                new IllegalStateException("test failure"),
                                failed,
                                progress);
                    }
                };
        try (BridgeServer bridge = server(config(availablePort(), 4), operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> partial =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edits",
                                    "{\"world\":\"world\",\"editIds\":[\""
                                            + undoneId
                                            + "\",\""
                                            + failedId
                                            + "\"]}"));
            HttpResponse<String> firstFailure =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edits",
                                    "{\"world\":\"world\",\"editIds\":[\"" + failedId + "\"]}"));

            assertEquals(503, partial.statusCode());
            var partialBody = json(partial.body()).getAsJsonObject();
            assertEquals(Set.of("error", "undoneEdits"), partialBody.keySet());
            assertEquals(
                    failedId.toString(),
                    partialBody.getAsJsonObject("error").get("editId").getAsString());
            assertEquals(1, partialBody.getAsJsonArray("undoneEdits").size());
            assertEquals(
                    "Undo completed prefix",
                    partialBody
                            .getAsJsonArray("undoneEdits")
                            .get(0)
                            .getAsJsonObject()
                            .get("label")
                            .getAsString());

            assertEquals(503, firstFailure.statusCode());
            var firstFailureBody = json(firstFailure.body()).getAsJsonObject();
            assertEquals(Set.of("error", "undoneEdits"), firstFailureBody.keySet());
            assertEquals(0, firstFailureBody.getAsJsonArray("undoneEdits").size());
        }
    }

    @Test
    void appliesConfiguredDefaultsAndGeneratesSeedsWhenOmitted() throws Exception {
        int port = availablePort();
        DirtConfig standard = config(port, 4);
        DirtConfig configured =
                new DirtConfig(
                        standard.bridge(),
                        standard.tools(),
                        standard.logging(),
                        standard.limits(),
                        standard.editHistory(),
                        new DirtConfig.Defaults(true, true));
        AtomicReference<GetBlocks.Request> blocksRequest = new AtomicReference<>();
        AtomicReference<SetBlocks.Request> setRequest = new AtomicReference<>();
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public GetBlocks.Result getBlocks(GetBlocks.Request request)
                            throws OperationException {
                        blocksRequest.set(request);
                        return new GetBlocks.Result(
                                request.world(), request.min(), List.of(), List.of(), List.of());
                    }

                    @Override
                    public SetBlocks.Result setBlocks(SetBlocks.Request request, UUID callId)
                            throws OperationException {
                        setRequest.set(request);
                        return super.setBlocks(request, callId);
                    }
                };
        try (BridgeServer bridge = server(configured, operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            String bounds =
                    "\"world\":\"world\",\"min\":{\"x\":0,\"y\":0,\"z\":0},"
                            + "\"max\":{\"x\":0,\"y\":0,\"z\":0}";
            assertEquals(
                    200,
                    send(client, post(bridge, "/v1/get-blocks", "{" + bounds + "}")).statusCode());
            HttpResponse<String> set =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/set-blocks",
                                    """
                                    {"world":"world","origin":{"x":0,"y":0,"z":0},
                                     "palettes":[[{"blockState":"minecraft:stone"}]],
                                     "placements":[[0,0,0,0]],"runs":[],
                                     "label":"Place default block"}
                                    """));

            assertTrue(blocksRequest.get().includeAir());
            assertEquals(321, blocksRequest.get().maxResults());
            assertTrue(setRequest.get().dryRun());
            assertEquals(
                    setRequest.get().seed(),
                    json(set.body()).getAsJsonObject().get("seed").getAsInt());
        }
    }

    @Test
    void rejectsInvalidBodiesBeforeCallingOperations() throws Exception {
        AtomicReference<CountRegionBlockStates.Request> called = new AtomicReference<>();
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public CountRegionBlockStates.Result countRegionBlockStates(
                            CountRegionBlockStates.Request request) throws OperationException {
                        called.set(request);
                        return super.countRegionBlockStates(request);
                    }
                };
        DirtConfig standard = config(availablePort(), 4);
        DirtConfig.Limits limits = standard.limits();
        DirtConfig small =
                new DirtConfig(
                        standard.bridge(),
                        standard.tools(),
                        standard.logging(),
                        new DirtConfig.Limits(
                                64,
                                limits.maxRegionVolume(),
                                limits.maxTouchedChunks(),
                                limits.maxInspectionTouchedChunks(),
                                limits.maxPerspectiveTouchedChunks(),
                                limits.maxBlockStatePatterns(),
                                limits.maxPaletteEntries(),
                                limits.maxChangedBlocks(),
                                limits.maxInspectionVolume(),
                                limits.maxPerspectiveRayDistanceBudget(),
                                limits.defaultInspectionResultLimit(),
                                limits.maxInspectionResultLimit(),
                                limits.maxPerspectiveRays(),
                                limits.maxCommandsPerRequest(),
                                limits.maxCommandFeedbackCharacters()),
                        standard.editHistory(),
                        standard.defaults());
        try (BridgeServer bridge = server(small, operations);
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            HttpResponse<String> contentType =
                    send(
                            client,
                            authorized(bridge, "/v1/count-region-block-states")
                                    .header("Content-Type", "text/plain")
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build());
            HttpResponse<String> oversized =
                    send(client, post(bridge, "/v1/count-region-block-states", " ".repeat(65)));
            HttpResponse<String> unknown =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/count-region-block-states",
                                    "{\"world\":\"world\",\"min\":{},\"max\":{},\"extra\":true}"));

            assertError(contentType, "Content-Type must be application/json");
            assertError(oversized, "Request body exceeds the maximum of 64 bytes");
            assertError(unknown, "Request contains missing or unknown fields");
            assertEquals(null, called.get());
        }
    }

    @Test
    void rejectsUnknownInspectionFieldAndUnsupportedViewDirection() throws Exception {
        try (BridgeServer bridge =
                        server(config(availablePort(), 4), new BridgeTestFixture.TestOperations());
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();

            HttpResponse<String> unknownField =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/get-blocks",
                                    """
                                    {"world":"world","min":{"x":0,"y":0,"z":0},
                                     "max":{"x":0,"y":0,"z":0},"unexpected":true}
                                    """));
            HttpResponse<String> direction =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/scan-orthographic-view",
                                    """
                                    {"world":"world","origin":{"x":0,"y":0,"z":0},
                                     "direction":"diagonal","horizontalRadius":0,
                                     "verticalRadius":0,"maxDistance":1}
                                    """));

            assertError(unknownField, "Request contains missing or unknown fields");
            assertEquals(
                    json("{reason:'unknown_fields',field:'unexpected'}"),
                    errorDetails(unknownField));
            assertError(direction, "direction must be north, east, south, west, up, or down");
            assertEquals(
                    json(
                            "{reason:'unsupported_value',target:'direction',"
                                    + "allowedValues:['north','east','south','west','up','down']}"),
                    errorDetails(direction));
        }
    }

    @Test
    void rejectsMalformedNumericValues() throws Exception {
        try (BridgeServer bridge =
                        server(config(availablePort(), 4), new BridgeTestFixture.TestOperations());
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            assertError(
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/set-blocks",
                                    """
                                    {"world":"world","origin":{"x":0,"y":0,"z":0},
                                     "palettes":[[{"blockState":"minecraft:stone"}]],
                                     "placements":[[0,1e2147483648,0,0]],"runs":[],
                                     "label":"Reject invalid coordinate"}
                                    """)),
                    "placements[0][1] must be a signed 32-bit integer");
            assertError(
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/set-blocks",
                                    """
                                    {"world":"world","origin":{"x":0,"y":0,"z":0},
                                     "palettes":[[{"blockState":"minecraft:stone"}]],
                                     "placements":[[0,0,0,0]],"runs":[],
                                     "label":"Reject unknown field","unexpected":true}
                                    """)),
                    "Request contains missing or unknown fields");
        }
    }

    @Test
    void requiresCanonicalVersionFourIdsForMutationsAndUndo() throws Exception {
        try (BridgeServer bridge =
                        server(config(availablePort(), 4), new BridgeTestFixture.TestOperations());
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            String setBlocks =
                    """
                    {"world":"world","origin":{"x":0,"y":0,"z":0},
                     "palettes":[[{"blockState":"minecraft:stone"}]],
                     "placements":[[0,0,0,0]],"runs":[],"label":"Place one block"}
                    """;
            HttpResponse<String> missingCallId =
                    send(
                            client,
                            authorized(bridge, "/v1/set-blocks")
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(setBlocks))
                                    .build());
            HttpResponse<String> invalidCallId =
                    send(
                            client,
                            authorized(bridge, "/v1/set-blocks")
                                    .header("Content-Type", "application/json")
                                    .header("X-Dirt-Call-Id", "not-a-uuid")
                                    .POST(HttpRequest.BodyPublishers.ofString(setBlocks))
                                    .build());
            HttpResponse<String> missingCommandCallId =
                    send(
                            client,
                            authorized(bridge, "/v1/run-minecraft-commands")
                                    .header("Content-Type", "application/json")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "{\"commands\":[\"help\"]}"))
                                    .build());
            HttpResponse<String> nonCanonicalCallId =
                    send(
                            client,
                            authorized(bridge, "/v1/set-blocks")
                                    .header("Content-Type", "application/json")
                                    .header("X-Dirt-Call-Id", "1-1-4000-8000-1")
                                    .POST(HttpRequest.BodyPublishers.ofString(setBlocks))
                                    .build());
            HttpResponse<String> wrongVersionCallId =
                    send(
                            client,
                            authorized(bridge, "/v1/set-blocks")
                                    .header("Content-Type", "application/json")
                                    .header(
                                            "X-Dirt-Call-Id",
                                            "123e4567-e89b-12d3-a456-426614174000")
                                    .POST(HttpRequest.BodyPublishers.ofString(setBlocks))
                                    .build());
            HttpResponse<String> invalidEditId =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edits",
                                    "{\"world\":\"world\",\"editIds\":[\"not-a-uuid\"]}"));
            HttpResponse<String> nonCanonicalEditId =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edits",
                                    "{\"world\":\"world\",\"editIds\":[\"1-1-4000-8000-1\"]}"));
            HttpResponse<String> wrongVersionEditId =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edits",
                                    "{\"world\":\"world\",\"editIds\":[\"123e4567-e89b-12d3-a456-426614174000\"]}"));
            HttpResponse<String> valid = send(client, post(bridge, "/v1/set-blocks", setBlocks));

            assertError(missingCallId, "X-Dirt-Call-Id must be a UUID version 4");
            assertError(missingCommandCallId, "X-Dirt-Call-Id must be a UUID version 4");
            assertError(invalidCallId, "X-Dirt-Call-Id must be a UUID version 4");
            assertError(nonCanonicalCallId, "X-Dirt-Call-Id must be a UUID version 4");
            assertError(wrongVersionCallId, "X-Dirt-Call-Id must be a UUID version 4");
            assertError(invalidEditId, "editIds[0] must be a UUID version 4");
            assertError(nonCanonicalEditId, "editIds[0] must be a UUID version 4");
            assertError(wrongVersionEditId, "editIds[0] must be a UUID version 4");
            assertEquals(200, valid.statusCode());
            assertEquals(
                    BridgeTestFixture.CALL_ID,
                    json(valid.body())
                            .getAsJsonObject()
                            .getAsJsonObject("edit")
                            .get("callId")
                            .getAsString());
        }
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request)
            throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertError(HttpResponse<String> response, String message) {
        assertEquals(400, response.statusCode());
        var error = json(response.body()).getAsJsonObject().getAsJsonObject("error");
        assertEquals("invalid_request", error.get("code").getAsString());
        assertEquals(message, error.get("message").getAsString());
        assertTrue(error.has("details"));
    }

    private static com.google.gson.JsonObject errorDetails(HttpResponse<String> response) {
        return json(response.body())
                .getAsJsonObject()
                .getAsJsonObject("error")
                .getAsJsonObject("details");
    }
}
