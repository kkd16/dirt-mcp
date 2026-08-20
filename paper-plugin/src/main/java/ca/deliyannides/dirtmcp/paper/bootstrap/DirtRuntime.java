package ca.deliyannides.dirtmcp.paper.bootstrap;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeServer;
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
import ca.deliyannides.dirtmcp.paper.command.BukkitCommandAccess;
import ca.deliyannides.dirtmcp.paper.command.PaperCommandService;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThread;
import ca.deliyannides.dirtmcp.paper.status.BukkitServerStatusAccess;
import ca.deliyannides.dirtmcp.paper.status.PaperServerStatusService;
import ca.deliyannides.dirtmcp.paper.world.edit.FaweWorldEditor;
import ca.deliyannides.dirtmcp.paper.world.edit.WorldEditLifecycleListener;
import ca.deliyannides.dirtmcp.paper.world.inspection.PaperRegionSnapshotSource;
import ca.deliyannides.dirtmcp.paper.world.inspection.RegionInspectionService;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns the Paper plugin's services and their shutdown order. */
public final class DirtRuntime implements AutoCloseable {
    private final PaperMainThread mainThread;
    private final FaweWorldEditor worldEditor;
    private final WorldEditLifecycleListener worldLifecycle;
    private final BridgeServer bridge;
    private final Logger logger;
    private final AtomicBoolean closed = new AtomicBoolean();

    private DirtRuntime(
            PaperMainThread mainThread,
            FaweWorldEditor worldEditor,
            WorldEditLifecycleListener worldLifecycle,
            BridgeServer bridge,
            Logger logger) {
        this.mainThread = mainThread;
        this.worldEditor = worldEditor;
        this.worldLifecycle = worldLifecycle;
        this.bridge = bridge;
        this.logger = logger;
    }

    public static DirtRuntime start(JavaPlugin plugin, DirtConfig config, String bearerToken)
            throws IOException {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(bearerToken, "bearerToken");

        PaperMainThread mainThread = new PaperMainThread(plugin);
        FaweWorldEditor worldEditor = null;
        WorldEditLifecycleListener worldLifecycle = null;
        BridgeServer bridge = null;
        try {
            PaperServerStatusService status =
                    new PaperServerStatusService(
                            mainThread, new BukkitServerStatusAccess(plugin, config));
            DirtConfig.Limits limits = config.limits();
            PaperCommandService commands =
                    new PaperCommandService(
                            mainThread,
                            new BukkitCommandAccess(plugin),
                            limits.maxCommandsPerRequest(),
                            limits.maxCommandFeedbackCharacters());
            RegionInspectionService inspection =
                    new RegionInspectionService(
                            new PaperRegionSnapshotSource(plugin.getServer(), mainThread),
                            limits.maxRegionVolume(),
                            limits.maxInspectionVolume(),
                            limits.maxInspectionResultLimit(),
                            limits.maxInspectionTouchedChunks(),
                            limits.maxBlockStatePatterns(),
                            config.bridge().maxConcurrentInspections());
            worldEditor = new FaweWorldEditor(plugin, mainThread, limits);
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
                                    new UndoLastEditEndpoint(worldEditor),
                                    new RunMinecraftCommandsEndpoint(commands)),
                            plugin.getLogger());
            bridge.start();
            return new DirtRuntime(
                    mainThread, worldEditor, worldLifecycle, bridge, plugin.getLogger());
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
            mainThread.close();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        HandlerList.unregisterAll(this.worldLifecycle);
        this.worldEditor.beginStopping();
        try {
            this.bridge.close();
        } finally {
            try {
                if (!this.worldEditor.closeIfQuiescent()) {
                    this.logger.severe(
                            "Dirt MCP left resources owned by an active edit intact after the "
                                    + "shutdown deadline; Paper will finish plugin cleanup.");
                }
            } finally {
                this.mainThread.close();
            }
        }
    }
}
