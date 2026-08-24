package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.bridge.endpoint.CapabilitiesEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.CountRegionBlockStatesEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.GetBlocksEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.GetEditHistoryEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.GetPerspectiveViewEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.GetPlayerContextEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.PingEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.ReplaceRegionBlocksEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.RunMinecraftCommandsEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.ScanOrthographicViewEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.ServerStatusEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.SetBlocksEndpoint;
import ca.deliyannides.dirtmcp.paper.bridge.endpoint.UndoEditsEndpoint;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus;
import ca.deliyannides.dirtmcp.paper.status.PingServer;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOperation;
import ca.deliyannides.dirtmcp.paper.world.edit.EditOutcome;
import ca.deliyannides.dirtmcp.paper.world.edit.EditRecord;
import ca.deliyannides.dirtmcp.paper.world.edit.EditStatus;
import ca.deliyannides.dirtmcp.paper.world.edit.GetEditHistory;
import ca.deliyannides.dirtmcp.paper.world.edit.ReplaceRegionBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.SetBlocks;
import ca.deliyannides.dirtmcp.paper.world.edit.UndoEdits;
import ca.deliyannides.dirtmcp.paper.world.inspection.CountRegionBlockStates;
import ca.deliyannides.dirtmcp.paper.world.inspection.ExactBlockStructure;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetBlocks;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPerspectiveView;
import ca.deliyannides.dirtmcp.paper.world.inspection.GetPlayerContext;
import ca.deliyannides.dirtmcp.paper.world.inspection.PlayerIdentity;
import ca.deliyannides.dirtmcp.paper.world.inspection.ScanOrthographicView;
import ca.deliyannides.dirtmcp.paper.world.model.BlockBounds;
import ca.deliyannides.dirtmcp.paper.world.model.BlockDimensions;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import ca.deliyannides.dirtmcp.paper.world.model.ExactPosition;
import ca.deliyannides.dirtmcp.paper.world.model.Rotation;
import ca.deliyannides.dirtmcp.paper.world.model.Vector3;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.helpers.NOPLogger;

final class BridgeTestFixture {
    static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String CALL_ID = "123e4567-e89b-42d3-a456-426614174000";
    static final UUID EDIT_ID = UUID.fromString("223e4567-e89b-42d3-a456-426614174000");
    static final UUID WORLD_ID = UUID.fromString("323e4567-e89b-42d3-a456-426614174000");

    private BridgeTestFixture() {}

    static DirtConfig config(int port, int maximumConcurrentRequests) {
        return new DirtConfig(
                new DirtConfig.Bridge(
                        port,
                        1,
                        1,
                        maximumConcurrentRequests,
                        1,
                        262_144,
                        List.of(BridgeOperation.values())),
                new DirtConfig.Logging(DirtConfig.ConsoleLogLevel.INFO, 10_485_760, 5),
                new DirtConfig.Limits(
                        1_000_000, 256, 32, 64, 64, 256, 250_000, 32_768, 65_536, 654, 512, 10,
                        8_192),
                new DirtConfig.EditHistory(20, 100, 1_000_000));
    }

    static BridgeServer server(DirtConfig config, TestOperations operations) {
        return server(
                config,
                operations,
                DirtLog.consoleOnly(NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR));
    }

    static BridgeServer server(DirtConfig config, TestOperations operations, DirtLog log) {
        return new BridgeServer(config, TOKEN, endpoints(config, operations), log);
    }

    static List<BridgeEndpoint> endpoints(DirtConfig config, TestOperations operations) {
        return List.of(
                new CapabilitiesEndpoint(config.bridge().allowedOperations()),
                new PingEndpoint(operations),
                new ServerStatusEndpoint(operations),
                new CountRegionBlockStatesEndpoint(operations),
                new GetBlocksEndpoint(operations),
                new ScanOrthographicViewEndpoint(operations),
                new GetPlayerContextEndpoint(operations),
                new GetPerspectiveViewEndpoint(operations),
                new ReplaceRegionBlocksEndpoint(operations),
                new SetBlocksEndpoint(operations),
                new GetEditHistoryEndpoint(operations),
                new UndoEditsEndpoint(operations),
                new RunMinecraftCommandsEndpoint(operations));
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
        return HttpRequest.newBuilder(uri(server, path))
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Dirt-Call-Id", CALL_ID);
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
                    GetBlocks,
                    ScanOrthographicView,
                    GetPlayerContext,
                    GetPerspectiveView,
                    ReplaceRegionBlocks,
                    SetBlocks,
                    GetEditHistory,
                    UndoEdits,
                    RunMinecraftCommands {
        @Override
        public PingServer.Result ping() throws OperationException {
            return new PingServer.Result("ok");
        }

        @Override
        public GetServerStatus.Result getStatus(GetServerStatus.Request request)
                throws OperationException {
            return new GetServerStatus.Result(
                    new GetServerStatus.Builds("26.2", "paper", "test", "fawe"),
                    new GetServerStatus.Performance(20.0, 1.25),
                    request.includePlayers()
                            ? new GetServerStatus.PlayerSummary(
                                    1,
                                    20,
                                    List.of(
                                            new GetServerStatus.OnlinePlayer(
                                                    "Builder",
                                                    "world",
                                                    "creative",
                                                    "north",
                                                    new BlockPosition(12, 70, -4))))
                            : null,
                    request.includeWorlds() ? List.of() : null,
                    request.includeConfiguration()
                            ? new GetServerStatus.EffectiveConfiguration(
                                    new GetServerStatus.EffectiveLimits(
                                            1_000_000, 256, 32, 64, 64, 256, 250_000, 32_768,
                                            65_536, 654, 512, 10, 8_192),
                                    new GetServerStatus.EffectiveEditHistory(20, 100, 1_000_000))
                            : null);
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
        public ExactBlockStructure getBlocks(GetBlocks.Request request) throws OperationException {
            return new ExactBlockStructure(
                    request.world(), request.min(), List.of(), List.of(), List.of());
        }

        @Override
        public ExactBlockStructure scanOrthographicView(ScanOrthographicView.Request request)
                throws OperationException {
            return new ExactBlockStructure(
                    request.world(), request.origin(), List.of(), List.of(), List.of());
        }

        @Override
        public GetPlayerContext.Result getPlayerContext(GetPlayerContext.Request request)
                throws OperationException {
            GetPlayerContext.Includes include = request.include();
            GetPlayerContext.Equipment equipment =
                    include.equipment()
                            ? new GetPlayerContext.Equipment(0, null, null, null, null, null, null)
                            : null;
            GetPlayerContext.InventoryContents inventory =
                    include.inventory()
                            ? new GetPlayerContext.InventoryContents(36, List.of())
                            : null;
            GetPlayerContext.InventoryContents enderChest =
                    include.enderChest()
                            ? new GetPlayerContext.InventoryContents(27, List.of())
                            : null;
            GetPlayerContext.PlayerVitals vitals =
                    include.vitals()
                            ? new GetPlayerContext.PlayerVitals(
                                    20, 20, 0, 20, 5, 0, 300, 300, 3, 0.5, 40, -1, 0)
                            : null;
            GetPlayerContext.PlayerMovement movement =
                    include.movement()
                            ? new GetPlayerContext.PlayerMovement(
                                    new Vector3(0, 0, 0),
                                    0,
                                    true,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false)
                            : null;
            GetPlayerContext.PlayerClient playerClient =
                    include.client()
                            ? new GetPlayerContext.PlayerClient(12, "en-US", 12, 10, 10)
                            : null;
            List<GetPlayerContext.Effect> effects = include.effects() ? List.of() : null;
            return new GetPlayerContext.Result(
                    Instant.parse("2026-08-19T12:00:00Z"),
                    new PlayerIdentity(
                            "Builder", UUID.fromString("423e4567-e89b-42d3-a456-426614174000")),
                    "world",
                    WORLD_ID,
                    new ExactPosition(12.5, 70, -3.25),
                    new BlockPosition(12, 70, -4),
                    new ExactPosition(12.5, 71.62, -3.25),
                    new Rotation(180, 0),
                    new Vector3(0, 0, -1),
                    "creative",
                    "standing",
                    true,
                    equipment,
                    inventory,
                    enderChest,
                    vitals,
                    movement,
                    playerClient,
                    effects);
        }

        @Override
        public GetPerspectiveView.Result getPerspectiveView(GetPerspectiveView.Request request)
                throws OperationException {
            GetPerspectiveView.ViewRequest options = request.options();
            double verticalScale =
                    Math.tan(Math.toRadians(options.verticalFieldOfViewDegrees()) / 2.0);
            double horizontalFov =
                    Math.toDegrees(
                            2 * Math.atan(verticalScale * options.width() / options.height()));
            GetPerspectiveView.ResolvedSource source =
                    request.source() instanceof GetPerspectiveView.PlayerSource
                            ? new GetPerspectiveView.ResolvedPlayerSource(
                                    new PlayerIdentity(
                                            "Builder",
                                            UUID.fromString(
                                                    "423e4567-e89b-42d3-a456-426614174000")))
                            : new GetPerspectiveView.ResolvedLocationSource();
            ExactPosition cameraPosition =
                    request.source() instanceof GetPerspectiveView.LocationSource location
                            ? location.cameraPosition()
                            : new ExactPosition(12.5, 71.62, -3.25);
            Rotation rotation =
                    request.source() instanceof GetPerspectiveView.LocationSource location
                            ? location.rotation()
                            : new Rotation(180, 0);
            return new GetPerspectiveView.Result(
                    Instant.parse("2026-08-19T12:00:00Z"),
                    source,
                    "world",
                    WORLD_ID,
                    cameraPosition,
                    rotation,
                    new Vector3(0, 0, -1),
                    new GetPerspectiveView.ViewBasis(
                            new Vector3(0, 0, -1), new Vector3(1, 0, 0), new Vector3(0, 1, 0)),
                    new GetPerspectiveView.Viewport(
                            options.width(),
                            options.height(),
                            options.verticalFieldOfViewDegrees(),
                            horizontalFov,
                            options.maxDistance(),
                            options.fluidCollision().name().toLowerCase(Locale.ROOT),
                            options.ignorePassableBlocks()),
                    1,
                    List.of(),
                    List.of(),
                    null);
        }

        @Override
        public RunMinecraftCommands.Result runCommands(RunMinecraftCommands.Request request)
                throws OperationException {
            return new RunMinecraftCommands.Result(
                    new RunMinecraftCommands.Sender("DirtMCP", true, false),
                    false,
                    request.commands().stream()
                            .map(
                                    command ->
                                            new RunMinecraftCommands.CommandResult(
                                                    command,
                                                    RunMinecraftCommands.Outcome.DISPATCHED,
                                                    List.of("ran " + command),
                                                    null,
                                                    null))
                            .toList());
        }

        @Override
        public ReplaceRegionBlocks.Result replaceRegionBlocks(
                ReplaceRegionBlocks.Request request, UUID callId) throws OperationException {
            BlockBounds bounds = new BlockBounds(request.min(), request.max());
            return new ReplaceRegionBlocks.Result(
                    request.world(),
                    bounds,
                    request.seed(),
                    outcome(request.dryRun()),
                    1,
                    1,
                    edit(
                            request.dryRun(),
                            request.world(),
                            bounds,
                            callId,
                            EditOperation.REPLACE_REGION_BLOCKS,
                            request.label()));
        }

        @Override
        public SetBlocks.Result setBlocks(SetBlocks.Request request, UUID callId)
                throws OperationException {
            long blockCount = request.placements().size();
            BlockBounds bounds = new BlockBounds(request.origin(), request.origin());
            return new SetBlocks.Result(
                    request.world(),
                    bounds,
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
                            EditOperation.SET_BLOCKS,
                            request.label()));
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
                                    EditOperation.SET_BLOCKS,
                                    EDIT_ID,
                                    "Place history block")));
        }

        @Override
        public UndoEdits.Result undoEdits(UndoEdits.Request request, UUID callId)
                throws OperationException {
            BlockBounds bounds =
                    new BlockBounds(new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0));
            return new UndoEdits.Completed(
                    request.world(),
                    request.editIds().stream()
                            .map(
                                    editId ->
                                            editRecord(
                                                    request.world(),
                                                    bounds,
                                                    UUID.fromString(CALL_ID),
                                                    EditOperation.SET_BLOCKS,
                                                    editId,
                                                    "Undo fixture edit"))
                            .toList(),
                    callId,
                    Instant.parse("2026-08-19T12:01:00Z"));
        }

        private static EditOutcome outcome(boolean dryRun) {
            return dryRun ? EditOutcome.PREVIEW : EditOutcome.COMMITTED;
        }

        private static EditRecord edit(
                boolean dryRun,
                String world,
                BlockBounds bounds,
                UUID callId,
                EditOperation operation,
                String label) {
            return dryRun ? null : editRecord(world, bounds, callId, operation, EDIT_ID, label);
        }

        private static EditRecord editRecord(
                String world,
                BlockBounds bounds,
                UUID callId,
                EditOperation operation,
                UUID editId,
                String label) {
            return new EditRecord(
                    editId,
                    callId,
                    operation,
                    label,
                    world,
                    WORLD_ID,
                    bounds,
                    1,
                    Instant.parse("2026-08-19T12:00:00Z"),
                    EditStatus.COMMITTED);
        }
    }
}
