package ca.deliyannides.dirtmcp.paper.bootstrap;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeServer;
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
import ca.deliyannides.dirtmcp.paper.command.DirtAdminCommand;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThread;
import ca.deliyannides.dirtmcp.paper.status.BukkitServerStatusAccess;
import ca.deliyannides.dirtmcp.paper.status.PaperServerStatusService;
import ca.deliyannides.dirtmcp.paper.world.edit.FaweWorldEditor;
import ca.deliyannides.dirtmcp.paper.world.edit.WorldEditLifecycleListener;
import ca.deliyannides.dirtmcp.paper.world.inspection.PaperRegionSnapshotSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionInspectionService;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns the Paper plugin's services and their shutdown order. */
public final class DirtRuntime implements AutoCloseable {
    private final PaperMainThread mainThread;
    private final FaweWorldEditor worldEditor;
    private final WorldEditLifecycleListener worldLifecycle;
    private final BridgeServer bridge;
    private final DirtLog log;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DirtRuntime(
            PaperMainThread mainThread,
            FaweWorldEditor worldEditor,
            WorldEditLifecycleListener worldLifecycle,
            BridgeServer bridge,
            DirtLog log) {
        this.mainThread = mainThread;
        this.worldEditor = worldEditor;
        this.worldLifecycle = worldLifecycle;
        this.bridge = bridge;
        this.log = log;
    }

    public static DirtRuntime start(
            JavaPlugin plugin, DirtConfig config, String bearerToken, DirtLog log)
            throws IOException {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(bearerToken, "bearerToken");
        Objects.requireNonNull(log, "log");

        PaperMainThread mainThread = new PaperMainThread(plugin);
        FaweWorldEditor worldEditor = null;
        WorldEditLifecycleListener worldLifecycle = null;
        BridgeServer bridge = null;
        try {
            PaperServerStatusService status =
                    new PaperServerStatusService(
                            mainThread, new BukkitServerStatusAccess(plugin, config));
            DirtConfig.Limits limits = config.limits();
            RegionInspectionService inspection =
                    new RegionInspectionService(
                            new PaperRegionSnapshotSource(plugin.getServer(), mainThread),
                            limits.maxRegionVolume(),
                            limits.maxInspectionVolume(),
                            limits.maxInspectionResultLimit(),
                            limits.maxInspectionTouchedChunks(),
                            limits.maxBlockStatePatterns(),
                            config.bridge().maxConcurrentInspections());
            worldEditor =
                    new FaweWorldEditor(plugin, mainThread, limits, config.editHistory(), log);
            worldLifecycle = new WorldEditLifecycleListener(worldEditor);
            plugin.getServer().getPluginManager().registerEvents(worldLifecycle, plugin);

            bridge =
                    new BridgeServer(
                            config,
                            bearerToken,
                            List.of(
                                    new PingEndpoint(status),
                                    new ServerStatusEndpoint(status),
                                    new CountRegionBlockStatesEndpoint(inspection),
                                    new GetRegionBlocksEndpoint(inspection, config),
                                    new ScanOrthographicViewEndpoint(inspection, config),
                                    new ReplaceRegionBlocksEndpoint(worldEditor, config),
                                    new FillRegionEndpoint(worldEditor, config),
                                    new SetBlocksEndpoint(worldEditor, config),
                                    new GetEditHistoryEndpoint(worldEditor),
                                    new UndoEditEndpoint(worldEditor)),
                            log);
            bridge.start();
            registerAdminCommand(plugin, config, status, log);
            String detailSummary =
                    log.hasDetailFile()
                            ? "detailed logs: " + plugin.getDataPath().resolve("logs")
                            : "detailed logging unavailable; see the prior console error";
            String message =
                    "Dirt MCP is ready on 127.0.0.1:" + bridge.boundPort() + "; " + detailSummary;
            LogContext context =
                    LogContext.of("plugin_version", plugin.getPluginMeta().getVersion())
                            .with("port", bridge.boundPort())
                            .with("enabled_tool_count", config.tools().enabled().size())
                            .with("detail_file_available", log.hasDetailFile());
            log.info("runtime", "runtime.started", message, context);
            return new DirtRuntime(mainThread, worldEditor, worldLifecycle, bridge, log);
        } catch (IOException | RuntimeException failure) {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (worldLifecycle != null) {
                try {
                    HandlerList.unregisterAll(worldLifecycle);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (worldEditor != null) {
                try {
                    worldEditor.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            try {
                mainThread.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void registerAdminCommand(
            JavaPlugin plugin, DirtConfig config, PaperServerStatusService status, DirtLog log) {
        PluginMeta metadata = plugin.getPluginMeta();
        DirtAdminCommand adminCommand =
                new DirtAdminCommand(
                        metadata.getName(), metadata.getVersion(), config, status, log);
        plugin.getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event ->
                                event.registrar()
                                        .register(
                                                adminCommand.command(),
                                                "Inspect Dirt MCP status, configuration, and tools"));
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        DirtLog ownedLog = this.log;
        PaperMainThread ownedMainThread = this.mainThread;
        try (ownedLog) {
            try (ownedMainThread) {
                HandlerList.unregisterAll(this.worldLifecycle);
                this.worldEditor.beginStopping();
                try {
                    this.bridge.close();
                } finally {
                    if (!this.worldEditor.closeIfQuiescent()) {
                        LogContext context = LogContext.of("resources_retained", true);
                        this.log.error(
                                "runtime",
                                "runtime.resources_retained",
                                "Dirt MCP left resources owned by an active edit after the "
                                        + "shutdown deadline",
                                context,
                                null);
                    }
                }
            } catch (RuntimeException | Error failure) {
                try {
                    LogContext context = LogContext.empty();
                    this.log.error(
                            "runtime",
                            "runtime.stop_failed",
                            "Dirt MCP encountered a failure while stopping",
                            context,
                            failure);
                } catch (RuntimeException | Error loggingFailure) {
                    failure.addSuppressed(loggingFailure);
                }
                throw failure;
            }
            LogContext context = LogContext.empty();
            this.log.info("runtime", "runtime.stopped", "Dirt MCP stopped", context);
        }
    }
}
