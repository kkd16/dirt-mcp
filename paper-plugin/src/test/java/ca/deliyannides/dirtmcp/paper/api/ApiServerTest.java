package ca.deliyannides.dirtmcp.paper.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.world.RegionInspector;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.EditException;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.FillResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.ReplaceResult;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionEditor.UndoResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockInspectionResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.BlockRun;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Bounds;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Dimensions;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ExactInspectionMode;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ExactInspectionRequest;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.ExactInspectionResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.Failure;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectedBlock;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionException;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.InspectionResult;
import ca.deliyannides.dirtmcp.paper.world.RegionInspector.RunInspectionResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class ApiServerTest {
    private static final Logger LOGGER = Logger.getLogger(ApiServerTest.class.getName());
    private static final String TOKEN = "test-token-with-at-least-thirty-two-bytes";
    private static final RegionInspector UNUSED_INSPECTOR = new TestInspector() {};
    private static final RegionEditor UNUSED_EDITOR = new TestEditor() {};

    @Test
    void reportsHealthOnLoopback() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    authorizedRequest(healthUri(server)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "application/json; charset=utf-8",
                    response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(
                    "{\"status\":\"ok\",\"service\":\"dirt-mcp-paper\",\"version\":\"0.1.0-test\","
                            + "\"minecraftVersion\":\"26.2\"}",
                    response.body());
        }
    }

    @Test
    void requiresBearerAuthentication() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(healthUri(server)).GET().build(),
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
                    authorizedRequest(healthUri(server))
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
    void inspectsARegion() throws Exception {
        RegionInspector inspector = new TestInspector() {
            @Override
            public InspectionResult inspect(RegionInspector.InspectionRequest request) {
                assertEquals("world", request.world());
                assertEquals(new BlockPosition(5, 60, -2), request.min());
                assertEquals(new BlockPosition(6, 61, -1), request.max());
                return new InspectionResult(
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
                    inspectionRequest(server, """
                            {"world":"world","min":{"x":5,"y":60,"z":-2},"max":{"x":6,"y":61,"z":-1}}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"bounds\":{\"min\":{\"x\":5,\"y\":60,\"z\":-2},"
                            + "\"max\":{\"x\":6,\"y\":61,\"z\":-1}},"
                            + "\"dimensions\":{\"x\":2,\"y\":2,\"z\":2},\"volume\":8,"
                            + "\"blockStates\":{\"minecraft:stone\":8}}",
                    response.body());
        }
    }

    @Test
    void inspectsExactBlocksWithSparseDefaults() throws Exception {
        RegionInspector inspector = new TestInspector() {
            @Override
            public ExactInspectionResult inspectBlocks(ExactInspectionRequest request) {
                assertEquals("world", request.world());
                assertEquals(List.of(), request.include());
                assertEquals(List.of(), request.exclude());
                assertEquals(false, request.includeAir());
                assertEquals(10_000, request.maxResults());
                assertEquals(ExactInspectionMode.BLOCKS, request.mode());
                return new BlockInspectionResult(
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
                    exactInspectionRequest(server, """
                            {"world":"world","min":{"x":1,"y":2,"z":3},"max":{"x":2,"y":2,"z":3}}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"bounds\":{\"min\":{\"x\":1,\"y\":2,\"z\":3},"
                            + "\"max\":{\"x\":2,\"y\":2,\"z\":3}},\"volume\":2,"
                            + "\"matchedBlocks\":1,\"mode\":\"blocks\",\"blocks\":[{\"position\":"
                            + "{\"x\":1,\"y\":2,\"z\":3},\"state\":\"minecraft:stone\"}]}",
                    response.body());
        }
    }

    @Test
    void inspectsExactRunsWithFilters() throws Exception {
        RegionInspector inspector = new TestInspector() {
            @Override
            public ExactInspectionResult inspectBlocks(ExactInspectionRequest request) {
                assertEquals(List.of("minecraft:spruce_log[axis=y]"), request.include());
                assertEquals(List.of("minecraft:air", "minecraft:snow"), request.exclude());
                assertEquals(true, request.includeAir());
                assertEquals(25, request.maxResults());
                assertEquals(ExactInspectionMode.RUNS, request.mode());
                return new RunInspectionResult(
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
                    exactInspectionRequest(server, """
                            {"world":"world","min":{"x":1,"y":2,"z":3},"max":{"x":1,"y":6,"z":3},
                             "include":["minecraft:spruce_log[axis=y]"],
                             "exclude":["minecraft:air","minecraft:snow"],"includeAir":true,
                             "maxResults":25,"mode":"runs"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"mode\":\"runs\""));
            assertTrue(response.body().contains("\"from\":{\"x\":1,\"y\":2,\"z\":3}"));
            assertTrue(response.body().contains("\"to\":{\"x\":1,\"y\":6,\"z\":3}"));
        }
    }

    @Test
    void rejectsInvalidExactInspectionOptions() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR); HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            HttpResponse<String> tooMany = client.send(
                    exactInspectionRequest(server, """
                            {"world":"world","min":{"x":0,"y":0,"z":0},"max":{"x":0,"y":0,"z":0},
                             "maxResults":10001}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> wrongMode = client.send(
                    exactInspectionRequest(server, """
                            {"world":"world","min":{"x":0,"y":0,"z":0},"max":{"x":0,"y":0,"z":0},
                             "mode":"cuboids"}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> wrongInclude = client.send(
                    exactInspectionRequest(server, """
                            {"world":"world","min":{"x":0,"y":0,"z":0},"max":{"x":0,"y":0,"z":0},
                             "include":"minecraft:stone"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(400, tooMany.statusCode());
            assertEquals(400, wrongMode.statusCode());
            assertEquals(400, wrongInclude.statusCode());
        }
    }

    @Test
    void rejectsUnknownFieldsAndNonIntegerCoordinates() throws Exception {
        try (ApiServer server = server(UNUSED_INSPECTOR); HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> unknownField = client.send(
                    inspectionRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},"max":{"x":1,"y":61,"z":1},"extra":true}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> fractionalCoordinate = client.send(
                    inspectionRequest(server, """
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
            public InspectionResult inspect(RegionInspector.InspectionRequest request)
                    throws InspectionException {
                throw new InspectionException(Failure.REGION_TOO_LARGE, "Region is too large");
            }
        };
        try (ApiServer server = server(inspector); HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    inspectionRequest(server, """
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
            public ReplaceResult replace(ReplaceRequest request) {
                assertEquals("world", request.world());
                assertEquals(new BlockPosition(5, 60, -2), request.min());
                assertEquals(new BlockPosition(6, 61, -1), request.max());
                assertEquals("minecraft:stone", request.source());
                assertEquals("minecraft:dirt", request.destination());
                assertEquals(false, request.dryRun());
                return new ReplaceResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        request.source(),
                        request.destination(),
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
                             "max":{"x":6,"y":61,"z":-1},"source":"minecraft:stone",
                             "destination":"minecraft:dirt"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"bounds\":{\"min\":{\"x\":5,\"y\":60,\"z\":-2},"
                            + "\"max\":{\"x\":6,\"y\":61,\"z\":-1}},"
                            + "\"source\":\"minecraft:stone\",\"destination\":\"minecraft:dirt\","
                            + "\"dryRun\":false,\"matchedBlocks\":8,\"changedBlocks\":8}",
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
                             "max":{"x":0,"y":60,"z":0},"source":"minecraft:stone"}
                            """),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> wrongDryRun = client.send(
                    replacementRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},
                             "max":{"x":0,"y":60,"z":0},"source":"minecraft:stone",
                             "destination":"minecraft:dirt","dryRun":"yes"}
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
            public ReplaceResult replace(ReplaceRequest request) throws EditException {
                throw new EditException(RegionEditor.Failure.WORLD_BUSY, "World is busy");
            }
        };
        try (ApiServer server = server(UNUSED_INSPECTOR, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    replacementRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},
                             "max":{"x":0,"y":60,"z":0},"source":"minecraft:stone",
                             "destination":"minecraft:dirt","dryRun":true}
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
            public FillResult fill(FillRequest request) {
                assertEquals("world", request.world());
                assertEquals(new BlockPosition(5, 60, -2), request.min());
                assertEquals(new BlockPosition(6, 61, -1), request.max());
                assertEquals("minecraft:oak_planks", request.destination());
                assertEquals(false, request.dryRun());
                return new FillResult(
                        request.world(),
                        new Bounds(request.min(), request.max()),
                        request.destination(),
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
                             "max":{"x":6,"y":61,"z":-1},"destination":"minecraft:oak_planks"}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(
                    "{\"world\":\"world\",\"bounds\":{\"min\":{\"x\":5,\"y\":60,\"z\":-2},"
                            + "\"max\":{\"x\":6,\"y\":61,\"z\":-1}},"
                            + "\"destination\":\"minecraft:oak_planks\",\"dryRun\":false,"
                            + "\"volume\":8,\"changedBlocks\":6}",
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
                             "max":{"x":0,"y":60,"z":0},"destination":"minecraft:dirt",
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
            public FillResult fill(FillRequest request) throws EditException {
                throw new EditException(RegionEditor.Failure.CHANGE_LIMIT_EXCEEDED, "Too many changes");
            }
        };
        try (ApiServer server = server(UNUSED_INSPECTOR, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    fillRequest(server, """
                            {"world":"world","min":{"x":0,"y":60,"z":0},
                             "max":{"x":0,"y":60,"z":0},"destination":"minecraft:dirt","dryRun":true}
                            """),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(413, response.statusCode());
            assertEquals(
                    "{\"error\":{\"code\":\"change_limit_exceeded\",\"message\":\"Too many changes\"}}",
                    response.body());
        }
    }

    @Test
    void undoesTheLastEdit() throws Exception {
        RegionEditor editor = new TestEditor() {
            @Override
            public UndoResult undo(UndoRequest request) {
                assertEquals("world", request.world());
                return new UndoResult(request.world(), 8);
            }
        };
        try (ApiServer server = server(UNUSED_INSPECTOR, editor);
                HttpClient client = HttpClient.newHttpClient()) {
            server.start();

            HttpResponse<String> response = client.send(
                    undoRequest(server, "{\"world\":\"world\"}"),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("{\"world\":\"world\",\"changedBlocks\":8}", response.body());
        }
    }

    @Test
    void reportsWhenThereIsNothingToUndo() throws Exception {
        RegionEditor editor = new TestEditor() {
            @Override
            public UndoResult undo(UndoRequest request) throws EditException {
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
    void interruptsActiveRequestsWhenClosed() throws Exception {
        CountDownLatch inspectionStarted = new CountDownLatch(1);
        CountDownLatch inspectionInterrupted = new CountDownLatch(1);
        RegionInspector inspector = new TestInspector() {
            @Override
            public InspectionResult inspect(RegionInspector.InspectionRequest request)
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
                    inspectionRequest(server, """
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

    private static ApiServer server(RegionInspector inspector, RegionEditor editor) {
        return new ApiServer(0, "0.1.0-test", "26.2", TOKEN, inspector, editor, LOGGER);
    }

    private static HttpRequest inspectionRequest(ApiServer server, String body) {
        return authorizedRequest(inspectRegionUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest replacementRequest(ApiServer server, String body) {
        return authorizedRequest(replaceBlocksUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest exactInspectionRequest(ApiServer server, String body) {
        return authorizedRequest(inspectBlocksUri(server))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static HttpRequest undoRequest(ApiServer server, String body) {
        return authorizedRequest(undoLastEditUri(server))
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

    private static HttpRequest.Builder authorizedRequest(URI uri) {
        return HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + TOKEN);
    }

    private static URI healthUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/health");
    }

    private static URI inspectRegionUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/inspect-region");
    }

    private static URI replaceBlocksUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/replace-blocks");
    }

    private static URI inspectBlocksUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/inspect-blocks");
    }

    private static URI undoLastEditUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/undo-last-edit");
    }

    private static URI fillRegionUri(ApiServer server) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/v1/fill-region");
    }

    private abstract static class TestEditor implements RegionEditor {
        @Override
        public ReplaceResult replace(ReplaceRequest request) throws EditException {
            throw new AssertionError("Region editor should not be called");
        }

        @Override
        public FillResult fill(FillRequest request) throws EditException {
            throw new AssertionError("Region editor should not be called");
        }

        @Override
        public UndoResult undo(UndoRequest request) throws EditException {
            throw new AssertionError("Region editor should not be called");
        }
    }

    private abstract static class TestInspector implements RegionInspector {
        @Override
        public InspectionResult inspect(RegionInspector.InspectionRequest request)
                throws InspectionException {
            throw new AssertionError("Region summary inspector should not be called");
        }

        @Override
        public ExactInspectionResult inspectBlocks(ExactInspectionRequest request)
                throws InspectionException {
            throw new AssertionError("Exact block inspector should not be called");
        }
    }
}
