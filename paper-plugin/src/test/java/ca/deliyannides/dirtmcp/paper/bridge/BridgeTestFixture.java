package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.bridge.endpoint.CountRegionBlockStatesEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.FillRegionEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.GetEditHistoryEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.GetRegionBlocksEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.PingEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.ReplaceRegionBlocksEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.ScanOrthographicViewEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.ServerStatusEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.SetBlocksEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.UndoEditEndpoint;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus;
import ca.deliyannides.dirtmcp.paper.status.PingServer;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOperation;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOutcome;
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
import ca.deliyannides.dirtmcp.paper.world.model.BlockDimensions;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

final class BridgeTestFixture {
    static final String TOKEN = "test-token-with-at-least-thirty-two-bytes";
    static final String CALL_ID = "123e4567-e89b-42d3-a456-426614174000";
    static final UUID EDIT_ID = UUID.fromString("223e4567-e89b-42d3-a456-426614174000");
    static final UUID WORLD_ID = UUID.fromString("323e4567-e89b-42d3-a456-426614174000");

    private BridgeTestFixture() {}

    static DirtConfig config(int port, int maximumConcurrentRequests) {
        return new DirtConfig(
                new DirtConfig.Bridge(port, 0, 1, 1, 32, maximumConcurrentRequests, 1),
                new DirtConfig.Limits(262_144, 1_000_000, 256, 32, 64, 250_000, 32_768, 321, 654),
                new DirtConfig.EditHistory(20, 100, 1_000_000),
                new DirtConfig.Defaults(false, "blocks", false));
    }

    static BridgeServer server(DirtConfig config, TestOperations operations) {
        return server(config, operations, Logger.getAnonymousLogger());
    }

    static BridgeServer server(DirtConfig config, TestOperations operations, Logger logger) {
        return new BridgeServer(
                config,
                TOKEN,
                List.of(
                        new PingEndpoint(operations),
                        new ServerStatusEndpoint(operations),
                        new CountRegionBlockStatesEndpoint(operations),
                        new GetRegionBlocksEndpoint(operations, config),
                        new ScanOrthographicViewEndpoint(operations, config),
                        new ReplaceRegionBlocksEndpoint(operations, config),
                        new FillRegionEndpoint(operations, config),
                        new SetBlocksEndpoint(operations, config),
                        new GetEditHistoryEndpoint(operations),
                        new UndoEditEndpoint(operations)),
                logger);
    }

    static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    static URI uri(BridgeServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.boundPort() + path);
    }

    static HttpRequest.Builder authorized(BridgeServer server, String path) {
        return HttpRequest.newBuilder(uri(server, path)).header("Authorization", "Bearer " + TOKEN);
    }

    static HttpRequest post(BridgeServer server, String path, String body) {
        return authorized(server, path)
                .header("X-Dirt-Call-Id", CALL_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    static JsonElement json(String value) {
        return JsonParser.parseString(value);
    }

    static class TestOperations
            implements PingServer,
                    GetServerStatus,
                    CountRegionBlockStates,
                    GetRegionBlocks,
                    ScanOrthographicView,
                    ReplaceRegionBlocks,
                    FillRegion,
                    SetBlocks,
                    GetEditHistory,
                    UndoEdit {
        @Override
        public PingServer.Result ping() throws OperationException {
            return new PingServer.Result("ok");
        }

        @Override
        public GetServerStatus.Result getStatus() throws OperationException {
            return new GetServerStatus.Result(
                    new GetServerStatus.Builds("26.2", "paper", "test", "fawe"),
                    new GetServerStatus.Performance(20.0, 1.25),
                    new GetServerStatus.PlayerSummary(
                            1,
                            20,
                            List.of(
                                    new GetServerStatus.OnlinePlayer(
                                            "Builder",
                                            "world",
                                            "creative",
                                            "north",
                                            new BlockPosition(12, 70, -4)))),
                    List.of(),
                    new GetServerStatus.EffectiveLimits(
                            262_144, 1_000_000, 256, 32, 64, 250_000, 32_768, 321, 654),
                    new GetServerStatus.EffectiveEditHistory(20, 100, 1_000_000),
                    new GetServerStatus.EffectiveDefaults(false, "blocks", false));
        }

        @Override
        public CountRegionBlockStates.Result countRegionBlockStates(
                CountRegionBlockStates.Request request) throws OperationException {
            return new CountRegionBlockStates.Result(
                    request.world(),
                    new BlockBounds(request.min(), request.max()),
                    new BlockDimensions(1, 1, 1),
                    1,
                    Map.of("minecraft:stone", 1L));
        }

        @Override
        public GetRegionBlocks.Result getRegionBlocks(GetRegionBlocks.Request request)
                throws OperationException {
            return new GetRegionBlocks.BlockListResult(
                    request.world(),
                    new BlockBounds(request.min(), request.max()),
                    1,
                    0,
                    "blocks",
                    List.of());
        }

        @Override
        public ScanOrthographicView.Result scanOrthographicView(
                ScanOrthographicView.Request request) throws OperationException {
            ScanOrthographicView.AxisVector forward = new ScanOrthographicView.AxisVector(0, 0, -1);
            return new ScanOrthographicView.Result(
                    request.world(),
                    request.origin(),
                    "north",
                    "orthographic",
                    new ScanOrthographicView.ViewBasis(
                            forward,
                            new ScanOrthographicView.AxisVector(1, 0, 0),
                            new ScanOrthographicView.AxisVector(0, 1, 0)),
                    new ScanOrthographicView.Viewport(
                            request.horizontalRadius(),
                            request.verticalRadius(),
                            request.maxDistance(),
                            request.depth()),
                    new BlockBounds(request.origin(), request.origin()),
                    1,
                    0,
                    List.of());
        }

        @Override
        public ReplaceRegionBlocks.Result replaceRegionBlocks(
                ReplaceRegionBlocks.Request request, UUID callId) throws OperationException {
            BlockBounds bounds = new BlockBounds(request.min(), request.max());
            return new ReplaceRegionBlocks.Result(
                    request.world(),
                    bounds,
                    request.sourceBlockStatePatterns(),
                    request.destinationPalette(),
                    request.seed(),
                    outcome(request.dryRun()),
                    1,
                    1,
                    edit(
                            request.dryRun(),
                            request.world(),
                            bounds,
                            callId,
                            EditOperation.REPLACE_REGION_BLOCKS));
        }

        @Override
        public FillRegion.Result fillRegion(FillRegion.Request request, UUID callId)
                throws OperationException {
            BlockBounds bounds = new BlockBounds(request.min(), request.max());
            return new FillRegion.Result(
                    request.world(),
                    bounds,
                    request.destinationPalette(),
                    request.seed(),
                    outcome(request.dryRun()),
                    1,
                    1,
                    edit(
                            request.dryRun(),
                            request.world(),
                            bounds,
                            callId,
                            EditOperation.FILL_REGION));
        }

        @Override
        public SetBlocks.Result setBlocks(SetBlocks.Request request, UUID callId)
                throws OperationException {
            long blockCount = request.placements().size();
            BlockBounds bounds = new BlockBounds(request.origin(), request.origin());
            return new SetBlocks.Result(
                    request.world(),
                    bounds,
                    request.palettes(),
                    request.seed(),
                    outcome(request.dryRun()),
                    blockCount,
                    1,
                    blockCount - 1,
                    edit(
                            request.dryRun(),
                            request.world(),
                            bounds,
                            callId,
                            EditOperation.SET_BLOCKS));
        }

        @Override
        public GetEditHistory.Result getEditHistory(GetEditHistory.Request request)
                throws OperationException {
            return new GetEditHistory.Result(
                    request.world(),
                    List.of(
                            editRecord(
                                    request.world(),
                                    new BlockBounds(
                                            new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0)),
                                    UUID.fromString(CALL_ID),
                                    EditOperation.FILL_REGION,
                                    EDIT_ID)));
        }

        @Override
        public UndoEdit.Result undoEdit(UndoEdit.Request request, UUID callId)
                throws OperationException {
            return new UndoEdit.Result(
                    editRecord(
                            request.world(),
                            new BlockBounds(new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0)),
                            UUID.fromString(CALL_ID),
                            EditOperation.FILL_REGION,
                            request.editId()),
                    callId,
                    "2026-08-19T12:01:00Z");
        }

        private static EditOutcome outcome(boolean dryRun) {
            return dryRun ? EditOutcome.PREVIEW : EditOutcome.COMMITTED;
        }

        private static EditRecord edit(
                boolean dryRun,
                String world,
                BlockBounds bounds,
                UUID callId,
                EditOperation operation) {
            return dryRun ? null : editRecord(world, bounds, callId, operation, EDIT_ID);
        }

        private static EditRecord editRecord(
                String world,
                BlockBounds bounds,
                UUID callId,
                EditOperation operation,
                UUID editId) {
            return new EditRecord(
                    editId,
                    callId,
                    operation,
                    world,
                    WORLD_ID,
                    bounds,
                    1,
                    "2026-08-19T12:00:00Z",
                    EditStatus.COMMITTED);
        }
    }
}
