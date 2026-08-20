package ca.deliyannides.dirtmcp.paper;

import ca.deliyannides.dirtmcp.paper.bootstrap.DirtRuntime;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.config.DirtConfigLoader;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import java.io.IOException;
import org.bukkit.plugin.java.JavaPlugin;

public final class DirtMcpPlugin extends JavaPlugin {
    private static final String PORT_ENVIRONMENT_VARIABLE = "DIRT_MCP_BRIDGE_PORT";
    private static final String TOKEN_ENVIRONMENT_VARIABLE = "DIRT_MCP_BRIDGE_TOKEN";

    private DirtRuntime runtime;

    @Override
    public void onEnable() {
        DirtConfig config = loadConfig();
        DirtLog log = DirtLog.open(this, config.logging());

        try {
            this.runtime = DirtRuntime.start(this, config, bridgeToken(), log);
        } catch (IOException exception) {
            this.runtime = null;
            LogContext context = LogContext.of("port", config.bridge().port());
            log.error(
                    "runtime",
                    "runtime.start_failed",
                    "Dirt MCP could not start its loopback bridge",
                    context,
                    exception);
            log.close();
            throw new IllegalStateException(
                    "Could not start the Dirt MCP bridge on 127.0.0.1:" + config.bridge().port(),
                    exception);
        } catch (RuntimeException exception) {
            this.runtime = null;
            LogContext context = LogContext.of("port", config.bridge().port());
            log.error(
                    "runtime",
                    "runtime.start_failed",
                    "Dirt MCP could not start",
                    context,
                    exception);
            log.close();
            throw exception;
        }
    }

    private DirtConfig loadConfig() {
        DirtLog bootstrapLog =
                DirtLog.consoleOnly(getSLF4JLogger(), DirtConfig.ConsoleLogLevel.ERROR);
        try {
            saveDefaultConfig();
            return DirtConfigLoader.load(getConfig(), System.getenv(PORT_ENVIRONMENT_VARIABLE));
        } catch (RuntimeException failure) {
            String detail = failure.getMessage() == null ? "" : ": " + failure.getMessage();
            String message = "Dirt MCP configuration could not be loaded" + detail;
            LogContext context = LogContext.empty();
            bootstrapLog.error(
                    "runtime", "runtime.configuration_failed", message, context, failure);
            throw failure;
        } finally {
            bootstrapLog.close();
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
