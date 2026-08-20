package ca.deliyannides.dirtmcp.paper;

import ca.deliyannides.dirtmcp.paper.bootstrap.DirtRuntime;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.config.DirtConfigLoader;
import java.io.IOException;
import org.bukkit.plugin.java.JavaPlugin;

public final class DirtMcpPlugin extends JavaPlugin {
    private static final String PORT_ENVIRONMENT_VARIABLE = "DIRT_MCP_BRIDGE_PORT";
    private static final String TOKEN_ENVIRONMENT_VARIABLE = "DIRT_MCP_BRIDGE_TOKEN";

    private DirtRuntime runtime;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        DirtConfig config =
                DirtConfigLoader.load(getConfig(), System.getenv(PORT_ENVIRONMENT_VARIABLE));

        try {
            this.runtime = DirtRuntime.start(this, config, bridgeToken());
        } catch (IOException exception) {
            this.runtime = null;
            throw new IllegalStateException(
                    "Could not start the Dirt MCP bridge on 127.0.0.1:" + config.bridge().port(),
                    exception);
        }
    }

    @Override
    public void onDisable() {
        if (this.runtime != null) {
            this.runtime.close();
            this.runtime = null;
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
