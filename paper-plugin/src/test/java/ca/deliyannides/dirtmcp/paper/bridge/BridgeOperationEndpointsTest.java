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

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.config.McpTool;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.DestinationPaletteEntry;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOperation;
import ca.deliyannides.dirtmcp.paper.world.edit.EditRecord;
import ca.deliyannides.dirtmcp.paper.world.edit.EditStatus;
import ca.deliyannides.dirtmcp.paper.world.edit.FillRegion;
import ca.deliyannides.dirtmcp.paper.world.edit.GetEditHistory;
import ca.deliyannides.dirtmcp.paper.world.edit.ReplaceRegionBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdit;
import ca.deliyannides.dirtmcp.paper.world.inspection.CountRegionBlockStates;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
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
        }
    }

    @Test
    void parsesAndDispatchesInspectionOperations() throws Exception {
        AtomicReference<CountRegionBlockStates.Request> countRequest = new AtomicReference<>();
        AtomicReference<GetRegionBlocks.Request> blocksRequest = new AtomicReference<>();
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
                    public GetRegionBlocks.Result getRegionBlocks(GetRegionBlocks.Request request)
                            throws OperationException {
                        blocksRequest.set(request);
                        return super.getRegionBlocks(request);
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
                                    "/v1/get-region-blocks",
                                    """
                                    {"world":"world","min":{"x":1,"y":2,"z":3},
                                     "max":{"x":1,"y":2,"z":3},
                                     "includeBlockStatePatterns":["minecraft:stone"],
                                     "excludeBlockStatePatterns":["minecraft:air"],
                                     "includeAir":true,"maxResults":25,"format":"runs"}
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
            assertEquals(GetRegionBlocks.Format.RUNS, blocksRequest.get().format());
            assertEquals(200, blocks.statusCode());
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
    void parsesAndDispatchesEditingOperations() throws Exception {
        AtomicReference<ReplaceRegionBlocks.Request> replaceRequest = new AtomicReference<>();
        AtomicReference<FillRegion.Request> fillRequest = new AtomicReference<>();
        AtomicReference<SetBlocks.Request> setRequest = new AtomicReference<>();
        AtomicReference<GetEditHistory.Request> historyRequest = new AtomicReference<>();
        AtomicReference<UndoEdit.Request> undoRequest = new AtomicReference<>();
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
                    public FillRegion.Result fillRegion(FillRegion.Request request, UUID callId)
                            throws OperationException {
                        fillRequest.set(request);
                        return super.fillRegion(request, callId);
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
                    public UndoEdit.Result undoEdit(UndoEdit.Request request, UUID callId)
                            throws OperationException {
                        undoRequest.set(request);
                        return super.undoEdit(request, callId);
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
                                     "seed":17,"dryRun":true}
                                    """));
            HttpResponse<String> fill =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/fill-region",
                                    """
                                    {"world":"world","min":{"x":0,"y":1,"z":2},
                                     "max":{"x":0,"y":1,"z":2},
                                     "destinationPalette":[{"blockState":"minecraft:dirt"}],
                                     "seed":19,"dryRun":true}
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
                                     "placements":[[0,0,0,0]],"seed":23,"dryRun":true}
                                    """));
            HttpResponse<String> history =
                    send(client, post(bridge, "/v1/get-edit-history", "{\"world\":\"world\"}"));
            HttpResponse<String> undo =
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
            assertEquals(40, replaceRequest.get().destinationPalette().getFirst().weight());
            assertEquals(200, fill.statusCode());
            assertEquals(19, fillRequest.get().seed());
            assertTrue(fillRequest.get().dryRun());
            assertFalse(json(fill.body()).toString().contains("weight"));
            assertEquals(200, set.statusCode());
            assertEquals(new BlockPosition(7, 8, 9), setRequest.get().origin());
            assertEquals(23, setRequest.get().seed());
            assertEquals(
                    List.of(
                            new DestinationPaletteEntry("minecraft:gold_block", 25),
                            new DestinationPaletteEntry("minecraft:iron_block", 75)),
                    setRequest.get().palettes().getFirst());
            assertEquals(0, setRequest.get().placements().getFirst().paletteIndex());
            assertEquals(
                    new SetBlocks.Placement(0, 0, 0, 0), setRequest.get().placements().getFirst());
            assertEquals(new GetEditHistory.Request("world"), historyRequest.get());
            assertEquals(200, history.statusCode());
            assertEquals(
                    new UndoEdit.Request("world", BridgeTestFixture.EDIT_ID), undoRequest.get());
            assertEquals(200, undo.statusCode());
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
                        EditOperation.FILL_REGION,
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
                    public UndoEdit.Result undoEdit(UndoEdit.Request request, UUID callId) {
                        return new UndoEdit.Result(
                                recovery, callId, Instant.parse("2026-08-19T12:05:00.000000Z"));
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
                                    "/v1/undo-edit",
                                    "{\"world\":\"world\",\"editId\":\"" + recoveryEditId + "\"}"));

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
                                  "operation":"fill_region",
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
                              "edit":{
                                "editId":"423e4567-e89b-42d3-a456-426614174000",
                                "callId":"523e4567-e89b-42d3-a456-426614174000",
                                "operation":"set_blocks",
                                "world":"world",
                                "worldId":"323e4567-e89b-42d3-a456-426614174000",
                                "bounds":{"min":{"x":-2,"y":64,"z":8},
                                          "max":{"x":3,"y":70,"z":12}},
                                "changedBlockCount":7,
                                "completedAt":"2026-08-19T12:02:03.120Z",
                                "status":"recovery_required"
                              },
                              "undoCallId":"123e4567-e89b-42d3-a456-426614174000",
                              "undoneAt":"2026-08-19T12:05:00Z"
                            }
                            """),
                    json(undo.body()));
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
                        new DirtConfig.Defaults(true, "runs", true));
        AtomicReference<GetRegionBlocks.Request> blocksRequest = new AtomicReference<>();
        AtomicReference<FillRegion.Request> firstFill = new AtomicReference<>();
        AtomicReference<SetBlocks.Request> setRequest = new AtomicReference<>();
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public GetRegionBlocks.Result getRegionBlocks(GetRegionBlocks.Request request)
                            throws OperationException {
                        blocksRequest.set(request);
                        return new GetRegionBlocks.BlockRunsResult(
                                request.world(),
                                new ca.deliyannides.dirtmcp.paper.world.model.BlockBounds(
                                        request.min(), request.max()),
                                1,
                                0,
                                "runs",
                                List.of());
                    }

                    @Override
                    public FillRegion.Result fillRegion(FillRegion.Request request, UUID callId)
                            throws OperationException {
                        firstFill.set(request);
                        return super.fillRegion(request, callId);
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
                    send(client, post(bridge, "/v1/get-region-blocks", "{" + bounds + "}"))
                            .statusCode());
            String fillBody =
                    "{" + bounds + ",\"destinationPalette\":[{\"blockState\":\"minecraft:dirt\"}]}";
            assertEquals(200, send(client, post(bridge, "/v1/fill-region", fillBody)).statusCode());
            HttpResponse<String> set =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/set-blocks",
                                    """
                                    {"world":"world","origin":{"x":0,"y":0,"z":0},
                                     "palettes":[[{"blockState":"minecraft:stone"}]],
                                     "placements":[[0,0,0,0]]}
                                    """));

            assertTrue(blocksRequest.get().includeAir());
            assertEquals(GetRegionBlocks.Format.RUNS, blocksRequest.get().format());
            assertEquals(321, blocksRequest.get().maxResults());
            assertTrue(firstFill.get().dryRun());
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
                                limits.maxBlockStatePatterns(),
                                limits.maxChangedBlocks(),
                                limits.maxInspectionVolume(),
                                limits.defaultInspectionResultLimit(),
                                limits.maxInspectionResultLimit()),
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
                                     "placements":[[0,1e2147483648,0,0]]}
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
                                     "placements":[[0,0,0,0]],"unexpected":true}
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
            String fill =
                    """
                    {"world":"world","min":{"x":0,"y":0,"z":0},
                     "max":{"x":0,"y":0,"z":0},
                     "destinationPalette":[{"blockState":"minecraft:stone"}]}
                    """;
            HttpResponse<String> missingCallId =
                    send(
                            client,
                            authorized(bridge, "/v1/fill-region")
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(fill))
                                    .build());
            HttpResponse<String> invalidCallId =
                    send(
                            client,
                            authorized(bridge, "/v1/fill-region")
                                    .header("Content-Type", "application/json")
                                    .header("X-Dirt-Call-Id", "not-a-uuid")
                                    .POST(HttpRequest.BodyPublishers.ofString(fill))
                                    .build());
            HttpResponse<String> nonCanonicalCallId =
                    send(
                            client,
                            authorized(bridge, "/v1/fill-region")
                                    .header("Content-Type", "application/json")
                                    .header("X-Dirt-Call-Id", "1-1-4000-8000-1")
                                    .POST(HttpRequest.BodyPublishers.ofString(fill))
                                    .build());
            HttpResponse<String> wrongVersionCallId =
                    send(
                            client,
                            authorized(bridge, "/v1/fill-region")
                                    .header("Content-Type", "application/json")
                                    .header(
                                            "X-Dirt-Call-Id",
                                            "123e4567-e89b-12d3-a456-426614174000")
                                    .POST(HttpRequest.BodyPublishers.ofString(fill))
                                    .build());
            HttpResponse<String> invalidEditId =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edit",
                                    "{\"world\":\"world\",\"editId\":\"not-a-uuid\"}"));
            HttpResponse<String> nonCanonicalEditId =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edit",
                                    "{\"world\":\"world\",\"editId\":\"1-1-4000-8000-1\"}"));
            HttpResponse<String> wrongVersionEditId =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/undo-edit",
                                    "{\"world\":\"world\",\"editId\":\"123e4567-e89b-12d3-a456-426614174000\"}"));
            HttpResponse<String> valid = send(client, post(bridge, "/v1/fill-region", fill));

            assertError(missingCallId, "X-Dirt-Call-Id must be a UUID version 4");
            assertError(invalidCallId, "X-Dirt-Call-Id must be a UUID version 4");
            assertError(nonCanonicalCallId, "X-Dirt-Call-Id must be a UUID version 4");
            assertError(wrongVersionCallId, "X-Dirt-Call-Id must be a UUID version 4");
            assertError(invalidEditId, "editId must be a UUID version 4");
            assertError(nonCanonicalEditId, "editId must be a UUID version 4");
            assertError(wrongVersionEditId, "editId must be a UUID version 4");
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
}
