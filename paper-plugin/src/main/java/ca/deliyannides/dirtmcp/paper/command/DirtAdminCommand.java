package ca.deliyannides.dirtmcp.paper.command;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.config.McpTool;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

/** Paper-facing operator command for inspecting the active Dirt MCP runtime. */
public final class DirtAdminCommand {
    public static final String PERMISSION = "dirtmcp.command";

    private static final TextColor ACCENT = TextColor.color(0x38BDF8);
    private static final TextColor SECONDARY_ACCENT = TextColor.color(0x22D3EE);
    private static final String DESCRIPTION = "Local-first access to live Minecraft worlds";

    private final String pluginName;
    private final String pluginVersion;
    private final DirtConfig config;
    private final GetServerStatus status;
    private final DirtLog log;

    public DirtAdminCommand(
            String pluginName,
            String pluginVersion,
            DirtConfig config,
            GetServerStatus status,
            DirtLog log) {
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        this.pluginVersion = Objects.requireNonNull(pluginVersion, "pluginVersion");
        this.config = Objects.requireNonNull(config, "config");
        this.status = Objects.requireNonNull(status, "status");
        this.log = Objects.requireNonNull(log, "log");
    }

    public LiteralCommandNode<CommandSourceStack> command() {
        return Commands.literal("dirt")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> showHelp(context.getSource().getSender()))
                .then(
                        Commands.literal("help")
                                .executes(context -> showHelp(context.getSource().getSender())))
                .then(
                        Commands.literal("version")
                                .executes(context -> showVersion(context.getSource().getSender())))
                .then(
                        Commands.literal("status")
                                .executes(context -> showStatus(context.getSource().getSender())))
                .then(
                        Commands.literal("config")
                                .executes(context -> showConfig(context.getSource().getSender())))
                .then(toolsCommand())
                .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> toolsCommand() {
        LiteralArgumentBuilder<CommandSourceStack> tools =
                Commands.literal("tools")
                        .executes(context -> showTools(context.getSource().getSender()));
        for (McpTool tool : McpTool.values()) {
            tools.then(
                    Commands.literal(tool.id())
                            .executes(context -> showTool(context.getSource().getSender(), tool)));
        }
        return tools;
    }

    private int showHelp(CommandSender sender) {
        TextComponent.Builder message = panel("Command Center");
        message.append(Component.newline());
        message.append(Component.text(DESCRIPTION, NamedTextColor.GRAY));
        message.append(Component.newline()).append(Component.newline());
        appendCommand(message, "/dirt status", "View live server and bridge status");
        appendCommand(message, "/dirt config", "Inspect the active configuration");
        appendCommand(message, "/dirt tools", "Browse MCP tool inputs and results");
        appendCommand(message, "/dirt version", "Show plugin version information");
        sender.sendMessage(message.build());
        return Command.SINGLE_SUCCESS;
    }

    private int showVersion(CommandSender sender) {
        TextComponent.Builder message = panel("Version");
        message.append(Component.newline());
        appendValue(message, "Plugin", this.pluginName);
        appendValue(message, "Version", this.pluginVersion);
        sender.sendMessage(message.build());
        return Command.SINGLE_SUCCESS;
    }

    private int showStatus(CommandSender sender) {
        final GetServerStatus.Result result;
        try {
            result = this.status.getStatus();
        } catch (OperationException exception) {
            LogContext context = LogContext.of("error_code", exception.failure());
            this.log.debug(
                    "admin",
                    "admin.status_failed",
                    "Dirt MCP could not read server status for the admin command",
                    context,
                    exception);
            TextComponent.Builder message = panel("Status");
            message.append(Component.newline());
            message.append(
                    Component.text("● Unavailable", NamedTextColor.RED, TextDecoration.BOLD));
            message.append(Component.newline());
            message.append(Component.text(exception.getMessage(), NamedTextColor.GRAY));
            sender.sendMessage(message.build());
            return 0;
        }

        TextComponent.Builder message = panel("Status");
        message.append(Component.newline());
        message.append(Component.text("● Running", NamedTextColor.GREEN, TextDecoration.BOLD));
        message.append(Component.newline()).append(Component.newline());
        appendValue(message, "Bridge", "127.0.0.1:" + this.config.bridge().port());
        appendValue(message, "Minecraft", result.builds().minecraft());
        appendValue(message, "Paper", result.builds().paper());
        appendValue(message, "Dirt MCP", result.builds().dirtMcp());
        appendValue(message, "FAWE", result.builds().fawe());
        appendValue(
                message,
                "Performance",
                String.format(
                        Locale.ROOT,
                        "%.2f TPS  •  %.2f ms/tick",
                        result.performance().tpsOneMinute(),
                        result.performance().averageTickTimeMillis()));
        appendValue(
                message,
                "Players",
                result.players().online() + " / " + result.players().maximum() + " online");
        appendValue(message, "Worlds", result.worlds().size() + " loaded");
        sender.sendMessage(message.build());
        return Command.SINGLE_SUCCESS;
    }

    private int showConfig(CommandSender sender) {
        TextComponent.Builder message = panel("Active Configuration");
        message.append(Component.newline());
        message.append(
                Component.text(
                        "Startup snapshot  •  restart Paper after file changes",
                        NamedTextColor.DARK_GRAY));

        DirtConfig.Bridge bridge = this.config.bridge();
        appendSection(message, "Bridge");
        appendValue(message, "port", bridge.port());
        appendValue(message, "shutdown-delay-seconds", bridge.shutdownDelaySeconds());
        appendValue(message, "request-body-timeout-seconds", bridge.requestBodyTimeoutSeconds());
        appendValue(message, "max-concurrent-requests", bridge.maxConcurrentRequests());
        appendValue(message, "max-concurrent-inspections", bridge.maxConcurrentInspections());

        appendSection(message, "Tools");
        for (McpTool tool : McpTool.values()) {
            appendValue(message, tool.id(), this.config.tools().isEnabled(tool));
        }

        DirtConfig.Logging logging = this.config.logging();
        appendSection(message, "Logging");
        appendValue(message, "console-level", logging.consoleLevel().configName());
        appendValue(message, "detail-file", DirtLog.DETAIL_FILE_PATTERN);
        appendValue(message, "detail-file-max-bytes", logging.detailFileMaxBytes());
        appendValue(message, "detail-file-retained-files", logging.detailFileRetainedFiles());

        DirtConfig.Limits limits = this.config.limits();
        appendSection(message, "Limits");
        appendValue(message, "max-request-bytes", limits.maxRequestBytes());
        appendValue(message, "max-region-volume", limits.maxRegionVolume());
        appendValue(message, "max-touched-chunks", limits.maxTouchedChunks());
        appendValue(message, "max-inspection-touched-chunks", limits.maxInspectionTouchedChunks());
        appendValue(message, "max-block-state-patterns", limits.maxBlockStatePatterns());
        appendValue(message, "max-changed-blocks", limits.maxChangedBlocks());
        appendValue(message, "max-inspection-volume", limits.maxInspectionVolume());
        appendValue(message, "default-inspection-results", limits.defaultInspectionResultLimit());
        appendValue(message, "max-inspection-results", limits.maxInspectionResultLimit());

        DirtConfig.EditHistory editHistory = this.config.editHistory();
        appendSection(message, "Edit History");
        appendValue(message, "max-entries-per-world", editHistory.maxEntriesPerWorld());
        appendValue(message, "max-entries-total", editHistory.maxEntriesTotal());
        appendValue(message, "max-retained-changed-blocks", editHistory.maxRetainedChangedBlocks());

        DirtConfig.Defaults defaults = this.config.defaults();
        appendSection(message, "Defaults");
        appendValue(message, "region-blocks-include-air", defaults.regionBlocksIncludeAir());
        appendValue(message, "region-blocks-format", defaults.regionBlocksFormat());
        appendValue(message, "edit-dry-run", defaults.editDryRun());

        sender.sendMessage(message.build());
        return Command.SINGLE_SUCCESS;
    }

    private int showTools(CommandSender sender) {
        int enabledCount = 0;
        for (McpTool tool : McpTool.values()) {
            if (this.config.tools().isEnabled(tool)) {
                enabledCount++;
            }
        }

        TextComponent.Builder message = panel("MCP Tools");
        message.append(Component.newline());
        message.append(
                Component.text(
                        "Paper startup snapshot  •  "
                                + enabledCount
                                + " of "
                                + McpTool.values().length
                                + " configured ON",
                        NamedTextColor.DARK_GRAY));
        message.append(Component.newline());
        for (McpTool tool : McpTool.values()) {
            McpToolHelp.ToolSpec spec = McpToolHelp.spec(tool);
            boolean enabled = this.config.tools().isEnabled(tool);
            String command = "/dirt tools " + tool.id();
            Component toolName =
                    Component.text(tool.id(), SECONDARY_ACCENT, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand(command))
                            .hoverEvent(
                                    HoverEvent.showText(
                                            Component.text(
                                                    "View " + spec.title(), NamedTextColor.GRAY)));
            message.append(Component.newline());
            message.append(
                    Component.text(
                            enabled ? "  ● ON   " : "  ○ OFF  ",
                            enabled ? NamedTextColor.GREEN : NamedTextColor.RED,
                            TextDecoration.BOLD));
            message.append(toolName);
            message.append(Component.text("  " + spec.purpose(), NamedTextColor.GRAY));
        }
        message.append(Component.newline()).append(Component.newline());
        message.append(
                Component.text(
                        "Click a tool for details. OFF tools are absent from a newly started MCP "
                                + "catalog; restart Paper, then the MCP host, after config changes.",
                        NamedTextColor.DARK_GRAY));
        sender.sendMessage(message.build());
        return Command.SINGLE_SUCCESS;
    }

    private int showTool(CommandSender sender, McpTool tool) {
        McpToolHelp.ToolSpec spec = McpToolHelp.spec(tool);
        boolean enabled = this.config.tools().isEnabled(tool);
        TextComponent.Builder message = panel("MCP Tool");
        message.append(Component.newline());
        message.append(Component.text(tool.id(), SECONDARY_ACCENT, TextDecoration.BOLD));
        message.append(Component.text("  /  " + spec.title(), NamedTextColor.WHITE));
        message.append(Component.newline());
        message.append(
                Component.text(
                        enabled ? "● CONFIGURED ON" : "○ CONFIGURED OFF",
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED,
                        TextDecoration.BOLD));
        message.append(Component.text("  •  Paper startup snapshot", NamedTextColor.DARK_GRAY));
        appendValue(message, "Type", spec.kind().label());

        appendSection(message, "Purpose");
        appendParagraph(message, spec.purpose());
        appendSection(message, "Arguments");
        appendParagraph(message, spec.arguments());
        appendSection(message, "Returns");
        appendParagraph(message, spec.returns());
        appendSection(message, "Behavior");
        appendParagraph(message, spec.notes());
        if (spec.usesEditRecords()) {
            appendParagraph(
                    message,
                    "EditRecord fields: editId, callId, operation, world, worldId, bounds, "
                            + "changedBlockCount, completedAt, and status.");
        }
        appendParagraph(
                message,
                "Canonical results are in structuredContent; text content is only a summary. "
                        + "Dirt-mapped failures use structuredContent.error.code, "
                        + "structuredContent.error.message, and structuredContent.error.callId, "
                        + "and may add structuredContent.error.editId for reconciliation. Invalid "
                        + "tool names or arguments fail before Dirt creates a callId; MCP SDK output "
                        + "validation occurs outside this mapping.");

        message.append(Component.newline()).append(Component.newline());
        message.append(
                Component.text("‹ Back to /dirt tools", SECONDARY_ACCENT)
                        .clickEvent(ClickEvent.runCommand("/dirt tools"))
                        .hoverEvent(
                                HoverEvent.showText(
                                        Component.text(
                                                "Open the MCP tool catalog",
                                                NamedTextColor.GRAY))));
        sender.sendMessage(message.build());
        return Command.SINGLE_SUCCESS;
    }

    private static TextComponent.Builder panel(String title) {
        return Component.text()
                .append(Component.text("◆ ", ACCENT))
                .append(Component.text("DIRT MCP", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("  /  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(title, SECONDARY_ACCENT, TextDecoration.BOLD));
    }

    private static void appendCommand(
            TextComponent.Builder message, String command, String description) {
        Component commandComponent =
                Component.text(command, SECONDARY_ACCENT)
                        .clickEvent(ClickEvent.suggestCommand(command))
                        .hoverEvent(
                                HoverEvent.showText(
                                        Component.text("Suggest " + command, NamedTextColor.GRAY)));
        message.append(Component.text("  › ", ACCENT));
        message.append(commandComponent);
        message.append(Component.text("  " + description, NamedTextColor.GRAY));
        message.append(Component.newline());
    }

    private static void appendSection(TextComponent.Builder message, String section) {
        message.append(Component.newline()).append(Component.newline());
        message.append(
                Component.text(section.toUpperCase(Locale.ROOT), ACCENT, TextDecoration.BOLD));
    }

    private static void appendParagraph(TextComponent.Builder message, String text) {
        message.append(Component.newline());
        message.append(Component.text("  " + text, NamedTextColor.WHITE));
    }

    private static void appendValue(TextComponent.Builder message, String label, Object value) {
        message.append(Component.newline());
        message.append(Component.text("  " + label + "  ", NamedTextColor.GRAY));
        message.append(Component.text(String.valueOf(value), NamedTextColor.WHITE));
    }
}
