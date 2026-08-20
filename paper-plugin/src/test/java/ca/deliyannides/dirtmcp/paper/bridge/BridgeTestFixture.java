package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.bridge.endpoint.CountRegionBlockStatesEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.FillRegionEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.GetRegionBlocksEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.PingEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.ReplaceRegionBlocksEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.RunMinecraftCommandsEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.ScanOrthographicViewEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.ServerStatusEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.SetBlocksEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.UndoLastEditEndpoint;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus;
import ca.deliyannides.dirtmcp.paper.status.PingServer;
import ca.deliyannides.dirtmcp.paper.world.edit.FillRegion;
import ca.deliyannides.dirtmcp.paper.world.edit.ReplaceRegionBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoLastEdit;
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
import java.util.logging.Logger;

final class BridgeTestFixture {
    static final String TOKEN = "test-token-with-at-least-thirty-two-bytes";

    private BridgeTestFixture() {}

    static DirtConfig config(int port, int maximumConcurrentRequests) {
        return new DirtConfig(
                new DirtConfig.Bridge(port, 0, 1, 1, 32, maximumConcurrentRequests, 1),
                new DirtConfig.Limits(
                        262_144, 1_000_000, 256, 32, 64, 250_000, 32_768, 321, 654, 20, 32_768, 20),
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
                        new UndoLastEditEndpoint(operations),
                        new RunMinecraftCommandsEndpoint(operations)),
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
                    UndoLastEdit,
                    RunMinecraftCommands {
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
                            262_144, 1_000_000, 256, 32, 64, 250_000, 32_768, 321, 654, 20, 32_768,
                            20),
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
                            request.maxDistance()),
                    new BlockBounds(request.origin(), request.origin()),
                    1,
                    0,
                    List.of());
        }

        @Override
        public ReplaceRegionBlocks.Result replaceRegionBlocks(ReplaceRegionBlocks.Request request)
                throws OperationException {
            return new ReplaceRegionBlocks.Result(
                    request.world(),
                    new BlockBounds(request.min(), request.max()),
                    request.sourceBlockStatePatterns(),
                    request.destinationPalette(),
                    request.seed(),
                    request.dryRun(),
                    1,
                    1);
        }

        @Override
        public FillRegion.Result fillRegion(FillRegion.Request request) throws OperationException {
            return new FillRegion.Result(
                    request.world(),
                    new BlockBounds(request.min(), request.max()),
                    request.destinationPalette(),
                    request.seed(),
                    request.dryRun(),
                    1,
                    1);
        }

        @Override
        public SetBlocks.Result setBlocks(SetBlocks.Request request) throws OperationException {
            return new SetBlocks.Result(
                    request.world(), request.dryRun(), request.changes().size(), 1, 0);
        }

        @Override
        public UndoLastEdit.Result undoLastEdit(UndoLastEdit.Request request)
                throws OperationException {
            return new UndoLastEdit.Result(request.world(), 1);
        }

        @Override
        public RunMinecraftCommands.Result runCommands(RunMinecraftCommands.Request request)
                throws OperationException {
            return new RunMinecraftCommands.Result(
                    new RunMinecraftCommands.Sender("DirtMCP", true, false),
                    false,
                    List.of(
                            new RunMinecraftCommands.CommandResult(
                                    request.commands().getFirst(),
                                    RunMinecraftCommands.Outcome.DISPATCHED,
                                    List.of("done"),
                                    null,
                                    null)));
        }
    }
}
