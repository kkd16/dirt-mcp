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
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.world.edit.FillRegion;
import ca.deliyannides.dirtmcp.paper.world.edit.ReplaceRegionBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoLastEdit;
import ca.deliyannides.dirtmcp.paper.world.inspection.CountRegionBlockStates;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetRegionBlocks;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BridgeOperationEndpointsTest {
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
                                     "maxDistance":12,"maxResults":40}
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
            assertEquals(40, viewRequest.get().maxResults());
            assertEquals(200, view.statusCode());
        }
    }

    @Test
    void parsesAndDispatchesEditingAndCommandOperations() throws Exception {
        AtomicReference<ReplaceRegionBlocks.Request> replaceRequest = new AtomicReference<>();
        AtomicReference<FillRegion.Request> fillRequest = new AtomicReference<>();
        AtomicReference<SetBlocks.Request> setRequest = new AtomicReference<>();
        AtomicReference<UndoLastEdit.Request> undoRequest = new AtomicReference<>();
        AtomicReference<RunMinecraftCommands.Request> commandRequest = new AtomicReference<>();
        BridgeTestFixture.TestOperations operations =
                new BridgeTestFixture.TestOperations() {
                    @Override
                    public ReplaceRegionBlocks.Result replaceRegionBlocks(
                            ReplaceRegionBlocks.Request request) throws OperationException {
                        replaceRequest.set(request);
                        return super.replaceRegionBlocks(request);
                    }

                    @Override
                    public FillRegion.Result fillRegion(FillRegion.Request request)
                            throws OperationException {
                        fillRequest.set(request);
                        return super.fillRegion(request);
                    }

                    @Override
                    public SetBlocks.Result setBlocks(SetBlocks.Request request)
                            throws OperationException {
                        setRequest.set(request);
                        return super.setBlocks(request);
                    }

                    @Override
                    public UndoLastEdit.Result undoLastEdit(UndoLastEdit.Request request)
                            throws OperationException {
                        undoRequest.set(request);
                        return super.undoLastEdit(request);
                    }

                    @Override
                    public RunMinecraftCommands.Result runCommands(
                            RunMinecraftCommands.Request request) throws OperationException {
                        commandRequest.set(request);
                        return super.runCommands(request);
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
                                    {"world":"world","changes":[{"position":{"x":7,"y":8,"z":9},
                                     "blockState":"minecraft:gold_block"}],"dryRun":true}
                                    """));
            HttpResponse<String> undo =
                    send(client, post(bridge, "/v1/undo-last-dirt-edit", "{\"world\":\"world\"}"));
            HttpResponse<String> commands =
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/run-minecraft-commands",
                                    "{\"commands\":[\"say hello\"]}"));

            assertEquals(200, replace.statusCode());
            assertEquals(17, replaceRequest.get().seed());
            assertTrue(replaceRequest.get().dryRun());
            assertEquals(40, replaceRequest.get().destinationPalette().getFirst().weight());
            assertEquals(200, fill.statusCode());
            assertEquals(19, fillRequest.get().seed());
            assertTrue(fillRequest.get().dryRun());
            assertFalse(json(fill.body()).toString().contains("weight"));
            assertEquals(200, set.statusCode());
            assertEquals(
                    new BlockPosition(7, 8, 9), setRequest.get().changes().getFirst().position());
            assertEquals(
                    "minecraft:gold_block", setRequest.get().changes().getFirst().blockState());
            assertEquals(new UndoLastEdit.Request("world"), undoRequest.get());
            assertEquals(200, undo.statusCode());
            assertEquals(List.of("say hello"), commandRequest.get().commands());
            assertEquals(200, commands.statusCode());
            assertEquals(
                    "dispatched",
                    json(commands.body())
                            .getAsJsonObject()
                            .getAsJsonArray("results")
                            .get(0)
                            .getAsJsonObject()
                            .get("outcome")
                            .getAsString());
        }
    }

    @Test
    void appliesConfiguredDefaultsAndGeneratesSeedsWhenOmitted() throws Exception {
        int port = availablePort();
        DirtConfig standard = config(port, 4);
        DirtConfig configured =
                new DirtConfig(
                        standard.bridge(),
                        standard.limits(),
                        new DirtConfig.Defaults(true, "runs", true));
        AtomicReference<GetRegionBlocks.Request> blocksRequest = new AtomicReference<>();
        AtomicReference<FillRegion.Request> firstFill = new AtomicReference<>();
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
                    public FillRegion.Result fillRegion(FillRegion.Request request)
                            throws OperationException {
                        firstFill.set(request);
                        return super.fillRegion(request);
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

            assertTrue(blocksRequest.get().includeAir());
            assertEquals(GetRegionBlocks.Format.RUNS, blocksRequest.get().format());
            assertEquals(321, blocksRequest.get().maxResults());
            assertTrue(firstFill.get().dryRun());
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
                        new DirtConfig.Limits(
                                64,
                                limits.maxRegionVolume(),
                                limits.maxTouchedChunks(),
                                limits.maxChangedBlocks(),
                                limits.maxInspectionVolume(),
                                limits.defaultInspectionResultLimit(),
                                limits.maxInspectionResultLimit(),
                                limits.maxCommandsPerRequest(),
                                limits.maxCommandFeedbackCharacters(),
                                limits.undoHistoryPerWorld()),
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
    void rejectsInvalidPalettesAndViewBounds() throws Exception {
        try (BridgeServer bridge =
                        server(config(availablePort(), 4), new BridgeTestFixture.TestOperations());
                HttpClient client = HttpClient.newHttpClient()) {
            bridge.start();
            String prefix =
                    "{\"world\":\"world\",\"min\":{\"x\":0,\"y\":0,\"z\":0},"
                            + "\"max\":{\"x\":0,\"y\":0,\"z\":0},\"destinationPalette\":";
            assertError(
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/fill-region",
                                    prefix
                                            + "[{\"blockState\":\"minecraft:dirt\",\"weight\":40}] }")),
                    "destinationPalette weights must total 100");
            assertError(
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/fill-region",
                                    prefix
                                            + "[{\"blockState\":\"minecraft:dirt\",\"weight\":50},"
                                            + "{\"blockState\":\"minecraft:stone\"}] }")),
                    "destinationPalette weights must be provided for every entry or omitted from every entry");
            assertError(
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/scan-orthographic-view",
                                    """
                                    {"world":"world","origin":{"x":0,"y":0,"z":0},
                                     "direction":"north","horizontalRadius":-1,"verticalRadius":0,
                                     "maxDistance":1}
                                    """)),
                    "horizontalRadius and verticalRadius must be non-negative");
            assertError(
                    send(
                            client,
                            post(
                                    bridge,
                                    "/v1/set-blocks",
                                    """
                                    {"world":"world","changes":[{"position":
                                     {"x":1e2147483648,"y":0,"z":0},
                                     "blockState":"minecraft:stone"}]}
                                    """)),
                    "changes[0].position.x must be a signed 32-bit integer");
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
    }
}
