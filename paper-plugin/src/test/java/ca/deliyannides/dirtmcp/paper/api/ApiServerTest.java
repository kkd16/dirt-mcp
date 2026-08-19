package ca.deliyannides.dirtmcp.paper.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.PluginSettings;
import ca.deliyannides.dirtmcp.paper.PluginSettings.Bridge;
import ca.deliyannides.dirtmcp.paper.PluginSettings.Defaults;
import ca.deliyannides.dirtmcp.paper.PluginSettings.Limits;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.CommandRunnerException;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.CommandOutcome;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.CommandResult;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.RunCommandsRequest;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.RunCommandsResult;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.Sender;
import ca.deliyannides.dirtmcp.paper.server.ServerContext;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.Builds;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.OnlinePlayer;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.Performance;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.PingResult;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.PlayerSummary;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.ServerStatus;
import ca.deliyannides.dirtmcp.paper.server.ServerContext.WorldStatus;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.EditException;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.BlockChange;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillRegionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillRegionResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRegionBlocksRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRegionBlocksResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.SetBlocksRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.SetBlocksResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoLastDirtEditRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoLastDirtEditResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RegionBlockListResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockRun;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Bounds;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Dimensions;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.AxisVector;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RegionBlocksFormat;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RegionBlocksRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RegionBlocksResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockStateCountResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RegionBlockRunsResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewBasis;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewBlock;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.OrthographicViewDirection;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ViewOffset;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.OrthographicViewRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.OrthographicViewResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Viewport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class ApiServerTest {
    private static final Logger LOGGER = Logger.getLogger(ApiServerTest.class.getName());
    private static final String TOKEN = "test-token-with-at-least-thirty-two-bytes";
    private static final PluginSettings SETTINGS = new PluginSettings(
            new Bridge(0, 0, 0, 8_192, 32),
            new Limits(
                    1_000_000,
                    250_000,
                    32_768,
                    321,
                    654,
                    32_768,
                    123,
                    456,
                    20,
                    32_768,
                    20),
            new Defaults(false, "blocks", false, false, false));
    private static final RegionInspector UNUSED_INSPECTOR = new TestInspector() {};
    private static final RegionEditor UNUSED_EDITOR = new TestEditor() {};
    private static final CommandRunner UNUSED_COMMAND_RUNNER = request -> {
        throw new AssertionError("Command runner should not be called");
    };
    private static final ServerContext SERVER_CONTEXT = new ServerContext() {
        @Override
        public PingResult ping() {
            return new PingResult("ok");
        }

        @Override
        public ServerStatus getStatus() {
            return new ServerStatus(
                    new Builds("26.2", "26.2-112-main", "0.1.0-test", "2.15.4-test"),
                    new Performance(19.98, 4.25),
                    new PlayerSummary(
                            1,
                            20,
                            List.of(new OnlinePlayer(
                                    "Builder",
                                    "world",
                                    "creative",
                                    new ServerContext.BlockPosition(12, 70, -4)))),
                    List.of(new WorldStatus(
                            "world",
                            "normal",
                            -64,
                            319,
                            new ServerContext.BlockPosition(0, 64, 0),
                            6000,
                            false,
                            false,
                            1)),
                    SETTINGS.limits(),
                    SETTINGS.defaults());
        }
    };

    @Test
    void reportsEndToEndPingOnLoopback() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    authorizedRequest(pingUri(server)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "application/json; charset=utf-8",
                    response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("{\"status\":\"ok\"}", response.body());
        }
    }

    @Test
    void reportsCurrentServerStatus() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    authorizedRequest(serverStatusUri(server)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"builds\":{\"minecraft\":\"26.2\",\"paper\":\"26.2-112-main\","
                            + "\"dirtMcp\":\"0.1.0-test\",\"fawe\":\"2.15.4-test\"},"
                            + "\"performance\":{\"tpsOneMinute\":19.98,\"averageTickTimeMillis\":4.25},"
                            + "\"players\":{\"online\":1,\"maximum\":20,\"entries\":[{"
                            + "\"name\":\"Builder\",\"world\":\"world\",\"gameMode\":\"creative\","
                            + "\"blockPosition\":{\"x\":12,\"y\":70,\"z\":-4}}]},\"worlds\":[{"
                            + "\"name\":\"world\",\"environment\":\"normal\",\"minY\":-64,\"maxY\":319,"
                            + "\"spawn\":{\"x\":0,\"y\":64,\"z\":0},\"timeOfDay\":6000,"
                            + "\"storm\":false,\"thundering\":false,\"playerCount\":1}],"
                            + "\"limits\":{\"maxRegionVolume\":1000000,\"maxChangedBlocks\":250000,"
                            + "\"maxRegionBlocksVolume\":32768,\"defaultRegionBlocksResultLimit\":321,"
                            + "\"maxRegionBlocksResultLimit\":654,\"maxOrthographicViewVolume\":32768,"
                            + "\"defaultOrthographicViewResultLimit\":123,\"maxOrthographicViewResultLimit\":456,"
                            + "\"maxCommandsPerRequest\":20,\"maxCommandFeedbackCharacters\":32768,"
                            + "\"undoHistoryPerWorld\":20},\"defaults\":{\"regionBlocksIncludeAir\":false,"
                            + "\"regionBlocksFormat\":\"blocks\",\"replaceRegionBlocksDryRun\":false,"
                            + "\"fillRegionDryRun\":false,\"setBlocksDryRun\":false}}",
                    response.body());
        }
    }

    @Test
    void requiresBearerAuthentication() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(pingUri(server)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
            assertEquals(
                    "Bearer realm=\"dirt-mcp\"",
                    response.headers().firstValue("WWW-Authenticate").orElseThrow());
            assertEquals(
                    "{\"error\":{\"code\":\"unauthorized\","
                            + "\"message\":\"A valid bearer token is required\"}}",
                    response.body());
        }
    }

    @Test
    void rejectsUnsupportedMethods() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    authorizedRequest(pingUri(server))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(405, response.statusCode());
            assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
            assertEquals(
                    "{\"error\":{\"code\":\"method_not_allowed\",\"message\":\"Method must be GET\"}}",
                    response.body());
        }
    }

    @Test
    void countsRegionBlockStates() throws Exception {
        RegionInspector inspector = new TestInspector() {
            @Override
            public BlockStateCountResult countRegionBlockStates(RegionInspector.BlockStateCountRequest request) {
                assertEquals("world", request.world());
                assertEquals(new BlockPosition(5, 60, -2), request.min());
                assertEquals(new BlockPosition(6, 61, -1), request.max());
                return new BlockStateCountResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        new Dimensions(2, 2, 2),
                        8,
                        Map.of("minecraft:stone", 8L));
            }
        };

        try (ApiServer server = server(inspector); HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    blockStateCountRequest(server, """
                            {"world":"world","min":{"x":5,"y":60,"z":-2},"max":{"x":6,"y":61,"z":-1}}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"bounds\":{\"min\":{\"x\":5,\"y\":60,\"z\":-2},"
                            + "\"max\":{\"x\":6,\"y\":61,\"z\":-1}},"
                            + "\"dimensions\":{\"x\":2,\"y\":2,\"z\":2},\"volume\":8,"
                            + "\"blockStateCounts\":{\"minecraft:stone\":8}}",
                    response.body());
        }
    }

    @Test
    void logsOneCorrelatedBridgeCallWithoutRequestContents() throws Exception {
        List<String> messages = new CopyOnWriteArrayList<>();
        CountDownLatch logged = new CountDownLatch(1);
        Logger auditLogger = Logger.getAnonymousLogger();
        auditLogger.setUseParentHandlers(false);
        auditLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
                logged.countDown();
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        RegionInspector inspector = new TestInspector() {
            @Override
            public BlockStateCountResult countRegionBlockStates(RegionInspector.BlockStateCountRequest request) {
                return new BlockStateCountResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        new Dimensions(1, 1, 1),
                        1,
                        Map.of("minecraft:stone", 1L));
            }
        };
        String callId = "123e4567-e89b-42d3-a456-426614174000";

        try (ApiServer server = server(inspector, UNUSED_EDITOR, auditLogger);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> response = client.send(
                    authorizedRequest(countRegionBlockStatesUri(server))
                            .header("Content-Type", "application/json")
                            .header("X-Dirt-Call-Id", callId)
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"world":"world","min":{"x":1,"y":2,"z":3},
                                     "max":{"x":1,"y":2,"z":3}}
                                    """))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(logged.await(2, TimeUnit.SECONDS));
            assertEquals(1, messages.size());
            assertTrue(messages.getFirst().matches(
                    "Dirt MCP bridge_call operation=count_region_block_states method=POST status=200 "
                            + "world=\\\"world\\\" call=" + callId + " duration_ms=\\d+"));
        }
    }

    @Test
    void getsRegionBlocksWithSparseDefaults() throws Exception {
        RegionInspector inspector = new TestInspector() {
            @Override
            public RegionBlocksResult getRegionBlocks(RegionBlocksRequest request) {
                assertEquals("world", request.world());
                assertEquals(List.of(), request.includeBlockStatePatterns());
                assertEquals(List.of(), request.excludeBlockStatePatterns());
                assertFalse(request.includeAir());
                assertEquals(321, request.maxResults());
                assertEquals(RegionBlocksFormat.BLOCKS, request.format());
                return new RegionBlockListResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        2,
                        1,
                        "blocks",
                        List.of(new InspectedBlock(request.min(), "minecraft:stone")));
            }
        };

        try (ApiServer server = server(inspector); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> response = client.send(
                    regionBlocksRequest(server, """
                            {"world":"world","min":{"x":1,"y":2,"z":3},"max":{"x":2,"y":2,"z":3}}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"bounds\":{\"min\":{\"x\":1,\"y\":2,\"z\":3},"
                            + "\"max\":{\"x\":2,\"y\":2,\"z\":3}},\"volume\":2,"
                            + "\"matchedBlockCount\":1,\"format\":\"blocks\",\"blocks\":[{\"position\":"
                            + "{\"x\":1,\"y\":2,\"z\":3},\"blockState\":\"minecraft:stone\"}]}",
                    response.body());
        }
    }

    @Test
    void getsRegionBlockRunsWithFilters() throws Exception {
        RegionInspector inspector = new TestInspector() {
            @Override
            public RegionBlocksResult getRegionBlocks(RegionBlocksRequest request) {
                assertEquals(List.of("minecraft:spruce_log[axis=y]"), request.includeBlockStatePatterns());
                assertEquals(List.of("minecraft:air", "minecraft:snow"), request.excludeBlockStatePatterns());
                assertTrue(request.includeAir());
                assertEquals(25, request.maxResults());
                assertEquals(RegionBlocksFormat.RUNS, request.format());
                return new RegionBlockRunsResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        5,
                        5,
                        "runs",
                        List.of(new BlockRun("minecraft:spruce_log[axis=y]", request.min(), request.max())));
            }
        };

        try (ApiServer server = server(inspector); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> response = client.send(
                    regionBlocksRequest(server, """
                            {"world":"world","min":{"x":1,"y":2,"z":3},"max":{"x":1,"y":6,"z":3},
                             "includeBlockStatePatterns":["minecraft:spruce_log[axis=y]"],
                             "excludeBlockStatePatterns":["minecraft:air","minecraft:snow"],"includeAir":true,
                             "maxResults":25,"format":"runs"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"bounds\":{\"min\":{\"x\":1,\"y\":2,\"z\":3},"
                            + "\"max\":{\"x\":1,\"y\":6,\"z\":3}},\"volume\":5,"
                            + "\"matchedBlockCount\":5,\"format\":\"runs\",\"runs\":[{"
                            + "\"blockState\":\"minecraft:spruce_log[axis\\u003dy]\","
                            + "\"from\":{\"x\":1,\"y\":2,\"z\":3},"
                            + "\"to\":{\"x\":1,\"y\":6,\"z\":3}}]}",
                    response.body());
        }
    }

    @Test
    void rejectsInvalidRegionBlockOptions() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> tooMany = client.send(
                    regionBlocksRequest(server, """
                            {"world":"world","min":{"x":0,"y":0,"z":0},"max":{"x":0,"y":0,"z":0},
                             "maxResults":655}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> wrongMode = client.send(
                    regionBlocksRequest(server, """
                            {"world":"world","min":{"x":0,"y":0,"z":0},"max":{"x":0,"y":0,"z":0},
                             "format":"cuboids"}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> wrongInclude = client.send(
                    regionBlocksRequest(server, """
                            {"world":"world","min":{"x":0,"y":0,"z":0},"max":{"x":0,"y":0,"z":0},
                             "includeBlockStatePatterns":"minecraft:stone"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, tooMany.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\","
                            + "\"message\":\"maxResults must be between 1 and 654\"}}",
                    tooMany.body());
            assertEquals(400, wrongMode.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\","
                            + "\"message\":\"format must be blocks or runs\"}}",
                    wrongMode.body());
            assertEquals(400, wrongInclude.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\","
                            + "\"message\":\"includeBlockStatePatterns must be an array of non-empty strings\"}}",
                    wrongInclude.body());
        }
    }

    @Test
    void enforcesJsonContentTypeAndRequestSize() throws Exception {
        PluginSettings settings = new PluginSettings(
                new Bridge(0, 0, 0, 64, 32),
                SETTINGS.limits(),
                SETTINGS.defaults());
        try (ApiServer server = server(settings, UNUSED_INSPECTOR, UNUSED_EDITOR);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> wrongContentType = client.send(
                    authorizedRequest(countRegionBlockStatesUri(server))
                            .header("Content-Type", "text/plain")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> oversized = client.send(
                    blockStateCountRequest(server, " ".repeat(65)),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> atLimit = client.send(
                    blockStateCountRequest(server, "{}" + " ".repeat(62)),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, wrongContentType.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\","
                            + "\"message\":\"Content-Type must be application/json\"}}",
                    wrongContentType.body());
            assertEquals(400, oversized.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\","
                            + "\"message\":\"Request body is too large\"}}",
                    oversized.body());
            assertEquals(400, atLimit.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\","
                            + "\"message\":\"Request contains missing or unknown fields\"}}",
                    atLimit.body());
        }
    }

    @Test
    void appliesConfiguredRequestDefaults() throws Exception {
        PluginSettings settings = new PluginSettings(
                SETTINGS.bridge(),
                SETTINGS.limits(),
                new Defaults(true, "runs", true, true, true));
        RegionInspector inspector = new TestInspector() {
            @Override
            public RegionBlocksResult getRegionBlocks(RegionBlocksRequest request) {
                assertTrue(request.includeAir());
                assertEquals(RegionBlocksFormat.RUNS, request.format());
                return new RegionBlockRunsResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        1,
                        0,
                        "runs",
                        List.of());
            }
        };
        RegionEditor editor = new TestEditor() {
            @Override
            public ReplaceRegionBlocksResult replaceRegionBlocks(ReplaceRegionBlocksRequest request) {
                assertTrue(request.dryRun());
                return new ReplaceRegionBlocksResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        request.sourceBlockState(),
                        request.destinationBlockState(),
                        request.dryRun(),
                        0,
                        0);
            }

            @Override
            public FillRegionResult fillRegion(FillRegionRequest request) {
                assertTrue(request.dryRun());
                return new FillRegionResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        request.blockState(),
                        request.dryRun(),
                        1,
                        0);
            }

            @Override
            public SetBlocksResult setBlocks(SetBlocksRequest request) {
                assertTrue(request.dryRun());
                return new SetBlocksResult(request.world(), true, request.changes().size(), 0, 1);
            }
        };

        try (ApiServer server = server(settings, inspector, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> exact = client.send(
                    regionBlocksRequest(server, """
                            {"world":"world","min":{"x":0,"y":0,"z":0},"max":{"x":0,"y":0,"z":0}}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> replace = client.send(
                    replacementRequest(server, """
                            {"world":"world","min":{"x":0,"y":0,"z":0},"max":{"x":0,"y":0,"z":0},
                             "sourceBlockState":"minecraft:stone","destinationBlockState":"minecraft:dirt"}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> fill = client.send(
                    fillRequest(server, """
                            {"world":"world","min":{"x":0,"y":0,"z":0},"max":{"x":0,"y":0,"z":0},
                             "blockState":"minecraft:dirt"}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> set = client.send(
                    setBlocksRequest(server, """
                            {"world":"world","changes":[{"position":{"x":0,"y":0,"z":0},
                             "blockState":"minecraft:dirt"}]}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, exact.statusCode());
            assertEquals(200, replace.statusCode());
            assertEquals(200, fill.statusCode());
            assertEquals(200, set.statusCode());
        }
    }

    @Test
    void mapsOversizedRegionBlockResultsToTheWireError() throws Exception {
        RegionInspector inspector = new TestInspector() {
            @Override
            public RegionBlocksResult getRegionBlocks(RegionBlocksRequest request)
                    throws InspectionException {
                throw new InspectionException(Failure.RESULT_TOO_LARGE, "Too many exact blocks");
            }
        };

        try (ApiServer server = server(inspector); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> response = client.send(
                    regionBlocksRequest(server, """
                            {"world":"world","min":{"x":0,"y":0,"z":0},"max":{"x":0,"y":0,"z":0}}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(413, response.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"result_too_large\","
                            + "\"message\":\"Too many exact blocks\"}}",
                    response.body());
        }
    }

    @Test
    void scansAnOrthographicViewWithTheBlocksFormatDefault() throws Exception {
        RegionInspector inspector = new TestInspector() {
            @Override
            public OrthographicViewResult scanOrthographicView(OrthographicViewRequest request) {
                assertEquals("world", request.world());
                assertEquals(new BlockPosition(1, 2, 3), request.origin());
                assertEquals(OrthographicViewDirection.NORTH, request.direction());
                assertEquals(1, request.horizontalRadius());
                assertEquals(1, request.verticalRadius());
                assertEquals(3, request.maxDistance());
                assertEquals(123, request.maxResults());
                return new OrthographicViewResult(
                        request.world(),
                        request.origin(),
                        "north",
                        "blocks",
                        new ViewBasis(
                                new AxisVector(0, 0, -1),
                                new AxisVector(1, 0, 0),
                                new AxisVector(0, 1, 0)),
                        new Viewport(1, 1, 3),
                        new Bounds(new BlockPosition(0, 1, 0), new BlockPosition(2, 3, 2)),
                        27,
                        1,
                        List.of(new ViewBlock(
                                new BlockPosition(1, 2, 2),
                                new ViewOffset(0, 0, 1),
                                "minecraft:oak_stairs[facing=north]")));
            }
        };

        try (ApiServer server = server(inspector); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> response = client.send(
                    viewRequest(server, """
                            {"world":"world","origin":{"x":1,"y":2,"z":3},
                             "direction":"north","horizontalRadius":1,"verticalRadius":1,
                             "maxDistance":3}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"origin\":{\"x\":1,\"y\":2,\"z\":3},"
                            + "\"direction\":\"north\",\"format\":\"blocks\","
                            + "\"basis\":{\"forward\":{\"x\":0,\"y\":0,\"z\":-1},"
                            + "\"horizontal\":{\"x\":1,\"y\":0,\"z\":0},"
                            + "\"vertical\":{\"x\":0,\"y\":1,\"z\":0}},"
                            + "\"viewport\":{\"horizontalRadius\":1,\"verticalRadius\":1,\"maxDistance\":3},"
                            + "\"bounds\":{\"min\":{\"x\":0,\"y\":1,\"z\":0},"
                            + "\"max\":{\"x\":2,\"y\":3,\"z\":2}},\"scannedVolume\":27,"
                            + "\"visibleBlockCount\":1,\"blocks\":[{\"position\":{\"x\":1,\"y\":2,\"z\":2},"
                            + "\"offset\":{\"horizontal\":0,\"vertical\":0,\"distance\":1},"
                            + "\"blockState\":\"minecraft:oak_stairs[facing\\u003dnorth]\"}]}",
                    response.body());
        }
    }

    @Test
    void rejectsInvalidViewOptionsAndUnknownFields() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            String base = """
                    {"world":"world","origin":{"x":0,"y":0,"z":0},
                     "direction":"%s","horizontalRadius":%d,"verticalRadius":1,
                     "maxDistance":3%s}
                    """;
            HttpResponse<String> direction = client.send(
                    viewRequest(server, base.formatted("forward", 1, "")),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> radius = client.send(
                    viewRequest(server, base.formatted("north", -1, "")),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> resultCap = client.send(
                    viewRequest(server, base.formatted("north", 1, ",\"maxResults\":457")),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> unknown = client.send(
                    viewRequest(server, base.formatted("north", 1, ",\"extra\":true")),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, direction.statusCode());
            assertEquals(400, radius.statusCode());
            assertEquals(400, resultCap.statusCode());
            assertEquals(400, unknown.statusCode());
        }
    }

    @Test
    void rejectsUnknownFieldsAndNonIntegerCoordinates() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR); HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> unknownField = client.send(
                    blockStateCountRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},"max":{"x":1,"y":61,"z":1},"extra":true}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> fractionalCoordinate = client.send(
                    blockStateCountRequest(server, """
                            {"world":"world","min":{"x":0.5,"y":60,"z":0},"max":{"x":1,"y":61,"z":1}}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, unknownField.statusCode());
            assertEquals(400, fractionalCoordinate.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\","
                            + "\"message\":\"min.x must be a signed 32-bit integer\"}}",
                    fractionalCoordinate.body());
        }
    }

    @Test
    void mapsInspectionFailuresToTheWireError() throws Exception {
        RegionInspector inspector = new TestInspector() {
            @Override
            public BlockStateCountResult countRegionBlockStates(RegionInspector.BlockStateCountRequest request)
                    throws InspectionException {
                throw new InspectionException(Failure.REGION_TOO_LARGE, "Region is too large");
            }
        };
        try (ApiServer server = server(inspector); HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    blockStateCountRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},"max":{"x":1,"y":61,"z":1}}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(413, response.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"region_too_large\",\"message\":\"Region is too large\"}}",
                    response.body());
        }
    }

    @Test
    void replacesBlocksWithDryRunDefaultingToFalse() throws Exception {
        RegionEditor editor = new TestEditor() {
            @Override
            public ReplaceRegionBlocksResult replaceRegionBlocks(ReplaceRegionBlocksRequest request) {
                assertEquals("world", request.world());
                assertEquals(new BlockPosition(5, 60, -2), request.min());
                assertEquals(new BlockPosition(6, 61, -1), request.max());
                assertEquals("minecraft:stone", request.sourceBlockState());
                assertEquals("minecraft:dirt", request.destinationBlockState());
                assertFalse(request.dryRun());
                return new ReplaceRegionBlocksResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        request.sourceBlockState(),
                        request.destinationBlockState(),
                        request.dryRun(),
                        8,
                        8);
            }
        };

        try (ApiServer server = server(UNUSED_INSPECTOR, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    replacementRequest(server, """
                            {"world":"world","min":{"x":5,"y":60,"z":-2},
                             "max":{"x":6,"y":61,"z":-1},"sourceBlockState":"minecraft:stone",
                             "destinationBlockState":"minecraft:dirt"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"bounds\":{\"min\":{\"x\":5,\"y\":60,\"z\":-2},"
                            + "\"max\":{\"x\":6,\"y\":61,\"z\":-1}},"
                            + "\"sourceBlockState\":\"minecraft:stone\",\"destinationBlockState\":\"minecraft:dirt\","
                            + "\"dryRun\":false,\"matchedBlockCount\":8,\"changedBlockCount\":8}",
                    response.body());
        }
    }

    @Test
    void rejectsInvalidReplacementFields() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR, UNUSED_EDITOR);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> missing = client.send(
                    replacementRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},
                             "max":{"x":0,"y":60,"z":0},"sourceBlockState":"minecraft:stone"}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> wrongDryRun = client.send(
                    replacementRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},
                             "max":{"x":0,"y":60,"z":0},"sourceBlockState":"minecraft:stone",
                             "destinationBlockState":"minecraft:dirt","dryRun":"yes"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, missing.statusCode());
            assertEquals(400, wrongDryRun.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\",\"message\":\"dryRun must be a boolean\"}}",
                    wrongDryRun.body());
        }
    }

    @Test
    void mapsEditFailuresToTheWireError() throws Exception {
        RegionEditor editor = new TestEditor() {
            @Override
            public ReplaceRegionBlocksResult replaceRegionBlocks(ReplaceRegionBlocksRequest request) throws EditException {
                throw new EditException(RegionEditor.Failure.WORLD_BUSY, "World is busy");
            }
        };
        try (ApiServer server = server(UNUSED_INSPECTOR, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    replacementRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},
                             "max":{"x":0,"y":60,"z":0},"sourceBlockState":"minecraft:stone",
                             "destinationBlockState":"minecraft:dirt","dryRun":true}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(409, response.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"world_busy\",\"message\":\"World is busy\"}}",
                    response.body());
        }
    }

    @Test
    void fillsARegionWithDryRunDefaultingToFalse() throws Exception {
        RegionEditor editor = new TestEditor() {
            @Override
            public FillRegionResult fillRegion(FillRegionRequest request) {
                assertEquals("world", request.world());
                assertEquals(new BlockPosition(5, 60, -2), request.min());
                assertEquals(new BlockPosition(6, 61, -1), request.max());
                assertEquals("minecraft:oak_planks", request.blockState());
                assertFalse(request.dryRun());
                return new FillRegionResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        request.blockState(),
                        request.dryRun(),
                        8,
                        6);
            }
        };

        try (ApiServer server = server(UNUSED_INSPECTOR, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    fillRequest(server, """
                            {"world":"world","min":{"x":5,"y":60,"z":-2},
                             "max":{"x":6,"y":61,"z":-1},"blockState":"minecraft:oak_planks"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"bounds\":{\"min\":{\"x\":5,\"y\":60,\"z\":-2},"
                            + "\"max\":{\"x\":6,\"y\":61,\"z\":-1}},"
                            + "\"blockState\":\"minecraft:oak_planks\",\"dryRun\":false,"
                            + "\"volume\":8,\"changedBlockCount\":6}",
                    response.body());
        }
    }

    @Test
    void rejectsInvalidFillFields() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR, UNUSED_EDITOR);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> missing = client.send(
                    fillRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},
                             "max":{"x":0,"y":60,"z":0}}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> wrongDryRun = client.send(
                    fillRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},
                             "max":{"x":0,"y":60,"z":0},"blockState":"minecraft:dirt",
                             "dryRun":"yes"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, missing.statusCode());
            assertEquals(400, wrongDryRun.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\",\"message\":\"dryRun must be a boolean\"}}",
                    wrongDryRun.body());
        }
    }

    @Test
    void mapsFillFailuresToTheWireError() throws Exception {
        RegionEditor editor = new TestEditor() {
            @Override
            public FillRegionResult fillRegion(FillRegionRequest request) throws EditException {
                throw new EditException(RegionEditor.Failure.CHANGE_LIMIT_EXCEEDED, "Too many changes");
            }
        };
        try (ApiServer server = server(UNUSED_INSPECTOR, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    fillRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},
                             "max":{"x":0,"y":60,"z":0},"blockState":"minecraft:dirt","dryRun":true}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(413, response.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"change_limit_exceeded\",\"message\":\"Too many changes\"}}",
                    response.body());
        }
    }

    @Test
    void setsExplicitBlocksWithDryRunDefaultingToFalse() throws Exception {
        RegionEditor editor = new TestEditor() {
            @Override
            public SetBlocksResult setBlocks(SetBlocksRequest request) {
                assertEquals("world", request.world());
                assertEquals(
                        List.of(
                                new BlockChange(
                                        new BlockPosition(5, 60, -2),
                                        "minecraft:oak_planks"),
                                new BlockChange(
                                        new BlockPosition(8, 63, 4),
                                        "minecraft:glass")),
                        request.changes());
                assertFalse(request.dryRun());
                return new SetBlocksResult(request.world(), false, 2, 1, 1);
            }
        };

        try (ApiServer server = server(UNUSED_INSPECTOR, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    setBlocksRequest(server, """
                            {"world":"world","changes":[
                              {"position":{"x":5,"y":60,"z":-2},
                               "blockState":"minecraft:oak_planks"},
                              {"position":{"x":8,"y":63,"z":4},
                               "blockState":"minecraft:glass"}]}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"dryRun\":false,\"blockCount\":2,"
                            + "\"changedBlockCount\":1,\"unchangedBlockCount\":1}",
                    response.body());
        }
    }

    @Test
    void rejectsInvalidSetBlocksFields() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR, UNUSED_EDITOR);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> empty = client.send(
                    setBlocksRequest(server, "{\"world\":\"world\",\"changes\":[]}"),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> unknownEntryField = client.send(
                    setBlocksRequest(server, """
                            {"world":"world","changes":[{"position":{"x":0,"y":60,"z":0},
                             "blockState":"minecraft:stone","extra":true}]}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, empty.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\","
                            + "\"message\":\"changes must be a non-empty array\"}}",
                    empty.body());
            assertEquals(400, unknownEntryField.statusCode());
        }
    }

    @Test
    void undoesTheLastEdit() throws Exception {
        RegionEditor editor = new TestEditor() {
            @Override
            public UndoLastDirtEditResult undoLastDirtEdit(UndoLastDirtEditRequest request) {
                assertEquals("world", request.world());
                return new UndoLastDirtEditResult(request.world(), 8);
            }
        };
        try (ApiServer server = server(UNUSED_INSPECTOR, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    undoRequest(server, "{\"world\":\"world\"}"),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("{\"world\":\"world\",\"changedBlockCount\":8}", response.body());
        }
    }

    @Test
    void reportsWhenThereIsNothingToUndo() throws Exception {
        RegionEditor editor = new TestEditor() {
            @Override
            public UndoLastDirtEditResult undoLastDirtEdit(UndoLastDirtEditRequest request) throws EditException {
                throw new EditException(RegionEditor.Failure.NOTHING_TO_UNDO, "Nothing to undo");
            }
        };
        try (ApiServer server = server(UNUSED_INSPECTOR, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    undoRequest(server, "{\"world\":\"world\"}"),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(409, response.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"nothing_to_undo\",\"message\":\"Nothing to undo\"}}",
                    response.body());
        }
    }

    @Test
    void runsAnOrderedMinecraftCommandBatch() throws Exception {
        CommandRunner commandRunner = request -> {
            assertEquals(List.of("say first", "missing", "say last"), request.commands());
            return new RunCommandsResult(
                    new Sender("FeedbackForwardingSender", true, false),
                    false,
                    List.of(
                            new CommandResult(
                                    "say first",
                                    CommandOutcome.DISPATCHED,
                                    List.of("[Dirt] first"),
                                    null),
                            new CommandResult(
                                    "missing",
                                    CommandOutcome.NOT_FOUND,
                                    List.of(),
                                    "Paper found no target for this command"),
                            new CommandResult(
                                    "say last",
                                    CommandOutcome.DISPATCHED,
                                    List.of("[Dirt] last"),
                                    null)));
        };

        try (ApiServer server = server(commandRunner);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    commandRequest(
                            server,
                            "{\"commands\":[\"say first\",\"missing\",\"say last\"]}"),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"sender\":{\"name\":\"FeedbackForwardingSender\",\"isOperator\":true,"
                            + "\"isPlayer\":false},\"feedbackTruncated\":false,\"results\":[{"
                            + "\"command\":\"say first\",\"outcome\":\"dispatched\","
                            + "\"feedback\":[\"[Dirt] first\"],\"message\":null},{"
                            + "\"command\":\"missing\",\"outcome\":\"not_found\","
                            + "\"feedback\":[],\"message\":\"Paper found no target for this command\"},{"
                            + "\"command\":\"say last\",\"outcome\":\"dispatched\","
                            + "\"feedback\":[\"[Dirt] last\"],\"message\":null}]}",
                    response.body());
        }
    }

    @Test
    void rejectsInvalidMinecraftCommandRequests() throws Exception {
        CommandRunner rejectingRunner = request -> {
            throw new CommandRunnerException(
                    CommandRunner.Failure.INVALID_REQUEST,
                    "commands must contain at least one command");
        };

        try (ApiServer server = server(rejectingRunner);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    commandRequest(server, "{\"commands\":[]}"),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"invalid_request\","
                            + "\"message\":\"commands must contain at least one command\"}}",
                    response.body());
        }
    }

    @Test
    void interruptsActiveRequestsWhenClosed() throws Exception {
        CountDownLatch inspectionStarted = new CountDownLatch(1);
        CountDownLatch inspectionInterrupted = new CountDownLatch(1);
        RegionInspector inspector = new TestInspector() {
            @Override
            public BlockStateCountResult countRegionBlockStates(RegionInspector.BlockStateCountRequest request)
                    throws InspectionException {
                inspectionStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("Inspection should have been interrupted");
                } catch (InterruptedException exception) {
                    inspectionInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new InspectionException(Failure.WORLD_UNAVAILABLE, "Interrupted", exception);
                }
            }
        };

        ApiServer server = server(inspector);
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            client.sendAsync(
                    blockStateCountRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},"max":{"x":0,"y":60,"z":0}}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertTrue(inspectionStarted.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), server::close);
            assertTrue(inspectionInterrupted.await(2, TimeUnit.SECONDS));
        } finally {
            server.close();
        }
    }

    private static ApiServer server(RegionInspector inspector) {
        return server(inspector, UNUSED_EDITOR);
    }

    private static ApiServer server(CommandRunner commandRunner) {
        return new ApiServer(
                SETTINGS,
                TOKEN,
                SERVER_CONTEXT,
                UNUSED_INSPECTOR,
                UNUSED_EDITOR,
                commandRunner,
                LOGGER);
    }

    private static ApiServer server(RegionInspector inspector, RegionEditor editor) {
        return server(inspector, editor, LOGGER);
    }

    private static ApiServer server(RegionInspector inspector, RegionEditor editor, Logger logger) {
        return server(SETTINGS, inspector, editor, logger);
    }

    private static ApiServer server(
            PluginSettings settings,
            RegionInspector inspector,
            RegionEditor editor) {
        return server(settings, inspector, editor, LOGGER);
    }

    private static ApiServer server(
            PluginSettings settings,
            RegionInspector inspector,
            RegionEditor editor,
            Logger logger) {
        return new ApiServer(
                settings,
                TOKEN,
                SERVER_CONTEXT,
                inspector,
                editor,
                UNUSED_COMMAND_RUNNER,
                logger);
    }

    private static HttpRequest blockStateCountRequest(ApiServer server, String body) {
        return authorizedRequest(countRegionBlockStatesUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest replacementRequest(ApiServer server, String body) {
        return authorizedRequest(replaceRegionBlocksUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest regionBlocksRequest(ApiServer server, String body) {
        return authorizedRequest(getRegionBlocksUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest commandRequest(ApiServer server, String body) {
        return authorizedRequest(runMinecraftCommandsUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest viewRequest(ApiServer server, String body) {
        return authorizedRequest(scanOrthographicViewUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest undoRequest(ApiServer server, String body) {
        return authorizedRequest(undoLastDirtEditUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest fillRequest(ApiServer server, String body) {
        return authorizedRequest(fillRegionUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest setBlocksRequest(ApiServer server, String body) {
        return authorizedRequest(setBlocksUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest.Builder authorizedRequest(URI uri) {
        return HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + TOKEN);
    }

    private static URI pingUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/ping");
    }

    private static URI serverStatusUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/server-status");
    }

    private static URI countRegionBlockStatesUri(ApiServer server) {
        return URI.create(
                "http://127.0.0.1:" + server.boundPort() + "/v1/count-region-block-states");
    }

    private static URI replaceRegionBlocksUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/replace-region-blocks");
    }

    private static URI getRegionBlocksUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/get-region-blocks");
    }

    private static URI scanOrthographicViewUri(ApiServer server) {
        return URI.create(
                "http://127.0.0.1:" + server.boundPort() + "/v1/scan-orthographic-view");
    }

    private static URI undoLastDirtEditUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/undo-last-dirt-edit");
    }

    private static URI fillRegionUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/fill-region");
    }

    private static URI setBlocksUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/set-blocks");
    }

    private static URI runMinecraftCommandsUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/run-minecraft-commands");
    }

    private abstract static class TestEditor implements RegionEditor {
        @Override
        public ReplaceRegionBlocksResult replaceRegionBlocks(ReplaceRegionBlocksRequest request) throws EditException {
            throw new AssertionError("Region editor should not be called");
        }

        @Override
        public FillRegionResult fillRegion(FillRegionRequest request) throws EditException {
            throw new AssertionError("Region editor should not be called");
        }

        @Override
        public SetBlocksResult setBlocks(SetBlocksRequest request) throws EditException {
            throw new AssertionError("Region editor should not be called");
        }

        @Override
        public UndoLastDirtEditResult undoLastDirtEdit(UndoLastDirtEditRequest request) throws EditException {
            throw new AssertionError("Region editor should not be called");
        }
    }

    private abstract static class TestInspector implements RegionInspector {
        @Override
        public BlockStateCountResult countRegionBlockStates(RegionInspector.BlockStateCountRequest request)
                throws InspectionException {
            throw new AssertionError("Region summary inspector should not be called");
        }

        @Override
        public RegionBlocksResult getRegionBlocks(RegionBlocksRequest request)
                throws InspectionException {
            throw new AssertionError("Exact block inspector should not be called");
        }

        @Override
        public OrthographicViewResult scanOrthographicView(OrthographicViewRequest request) throws InspectionException {
            throw new AssertionError("View inspector should not be called");
        }
    }
}
