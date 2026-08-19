package ca.deliyannides.dirtmcp.paper;

import ca.deliyannides.dirtmcp.paper.api.ApiServer;
import ca.deliyannides.dirtmcp.paper.server.PaperServerContext;
import ca.deliyannides.dirtmcp.paper.world.FaweRegionEditor;
import ca.deliyannides.dirtmcp.paper.world.PaperRegionInspector;
import java.io.IOException;
import org.bukkit.plugin.java.JavaPlugin;

public final class DirtMcpPlugin extends JavaPlugin {
    private static final String PORT_ENVIRONMENT_VARIABLE = "DIRT_MCP_BRIDGE_PORT";
    private static final String TOKEN_ENVIRONMENT_VARIABLE = "DIRT_MCP_BRIDGE_TOKEN";

    private ApiServer apiServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        boolean hasMissingDefaults = getConfig().getDefaults() != null
                && getConfig().getDefaults().getKeys(true).stream()
                        .anyMatch(path -> !getConfig().isSet(path));
        getConfig().options().copyDefaults(true);
        if (hasMissingDefaults) {
            saveConfig();
        }

        PluginSettings settings = PluginSettings.load(
                getConfig(), System.getenv(PORT_ENVIRONMENT_VARIABLE));
        PluginSettings.Limits limits = settings.limits();
        this.apiServer = new ApiServer(
                settings,
                bridgeToken(),
                new PaperServerContext(this, settings),
                new PaperRegionInspector(
                        this,
                        limits.maxRegionVolume(),
                        limits.maxRegionBlocksVolume(),
                        limits.maxRegionBlocksResultLimit(),
                        limits.maxOrthographicViewVolume(),
                        limits.maxOrthographicViewResultLimit()),
                new FaweRegionEditor(
                        this,
                        limits.maxRegionVolume(),
                        limits.maxChangedBlocks(),
                        limits.undoHistoryPerWorld()),
                getLogger());

        try {
            this.apiServer.start();
        } catch (IOException exception) {
            this.apiServer = null;
            throw new IllegalStateException(
                    "Could not start the Dirt MCP bridge on 127.0.0.1:"
                            + settings.bridge().port(),
                    exception);
        }

        getLogger().info(
                "Dirt MCP bridge listening on http://127.0.0.1:"
                        + settings.bridge().port());
    }

    @Override
    public void onDisable() {
        if (this.apiServer != null) {
            this.apiServer.close();
            this.apiServer = null;
        }
    }

    private static String bridgeToken() {
        String token = System.getenv(TOKEN_ENVIRONMENT_VARIABLE);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(TOKEN_ENVIRONMENT_VARIABLE + " is required");
        }
        return token;
    }
}
