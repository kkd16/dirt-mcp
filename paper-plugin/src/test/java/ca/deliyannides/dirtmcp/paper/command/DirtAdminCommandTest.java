package ca.deliyannides.dirtmcp.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.config.McpTool;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

final class DirtAdminCommandTest {
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    @Test
    void rootAndHelpSubcommandRenderTheCommandMenu() throws Exception {
        var fixture = fixture(true, status());

        assertEquals(1, fixture.execute("dirt"));
        assertEquals(1, fixture.execute("dirt help"));

        assertEquals(2, fixture.messages.size());
        for (Component message : fixture.messages) {
            String plain = PLAIN.serialize(message);
            assertTrue(plain.contains("DIRT MCP  /  Command Center"));
            assertTrue(plain.contains("/dirt status"));
            assertTrue(plain.contains("/dirt config"));
            assertTrue(plain.contains("/dirt tools"));
            assertTrue(plain.contains("/dirt version"));
            assertTrue(containsClickEvent(message));
        }
    }

    @Test
    void versionUsesPackagedMetadata() throws Exception {
        var fixture = fixture(true, status());

        assertEquals(1, fixture.execute("dirt version"));

        Component message = fixture.messages.getFirst();
        String plain = PLAIN.serialize(message);
        assertTrue(plain.contains("DIRT MCP  /  Version"));
        assertTrue(plain.contains("Plugin  DirtMCP"));
        assertTrue(plain.contains("Version  0.1.0-SNAPSHOT"));
    }

    @Test
    void statusRendersCompactOperationalSnapshot() throws Exception {
        var fixture = fixture(true, status());

        assertEquals(1, fixture.execute("dirt status"));

        String plain = PLAIN.serialize(fixture.messages.getFirst());
        assertTrue(plain.contains("DIRT MCP  /  Status"));
        assertTrue(plain.contains("● Running"));
        assertTrue(plain.contains("Bridge  127.0.0.1:8765"));
        assertTrue(plain.contains("Minecraft  26.2"));
        assertTrue(plain.contains("Paper  Paper build 112"));
        assertTrue(plain.contains("Dirt MCP  0.1.0-SNAPSHOT"));
        assertTrue(plain.contains("FAWE  2.15.4"));
        assertTrue(plain.contains("Performance  19.95 TPS  •  4.25 ms/tick"));
        assertTrue(plain.contains("Players  1 / 20 online"));
        assertTrue(plain.contains("Worlds  1 loaded"));
    }

    @Test
    void statusReportsExpectedOperationalFailureWithoutThrowing() throws Exception {
        OperationException expected =
                new OperationException(
                        OperationFailure.SERVER_UNAVAILABLE,
                        "FAWE is not available",
                        new ErrorDetails.ServerUnavailable.DependencyUnavailable());
        List<LogRecord> records = new ArrayList<>();
        DirtLog log = recordingLog(records);
        var fixture =
                fixture(
                        true,
                        command(
                                () -> {
                                    throw expected;
                                },
                                log));

        assertEquals(0, fixture.execute("dirt status"));

        String plain = PLAIN.serialize(fixture.messages.getFirst());
        assertTrue(plain.contains("● Unavailable"));
        assertTrue(plain.contains("FAWE is not available"));
        assertEquals(1, records.size());
        LogRecord record = records.getFirst();
        assertEquals(Level.FINE, record.getLevel());
        assertEquals("admin.status_failed", record.getLoggerName());
        assertEquals(expected, record.getThrown());
        LogContext context = (LogContext) record.getParameters()[0];
        assertEquals("server_unavailable", context.values().get("error_code"));
        log.close();
    }

    @Test
    void configRendersEveryEffectiveSetting() throws Exception {
        var fixture = fixture(true, status());

        assertEquals(1, fixture.execute("dirt config"));

        String plain = PLAIN.serialize(fixture.messages.getFirst());
        assertTrue(plain.contains("DIRT MCP  /  Active Configuration"));
        assertTrue(plain.contains("restart Paper after file changes"));
        assertTrue(plain.contains("port  8765"));
        assertTrue(plain.contains("shutdown-delay-seconds  5"));
        assertTrue(plain.contains("request-body-timeout-seconds  6"));
        assertTrue(plain.contains("max-concurrent-requests  30"));
        assertTrue(plain.contains("max-concurrent-inspections  2"));
        assertTrue(plain.contains("TOOLS"));
        assertTrue(plain.contains("ping_server  true"));
        assertTrue(plain.contains("get_server_status  false"));
        assertTrue(plain.contains("count_region_block_states  true"));
        assertTrue(plain.contains("get_region_blocks  false"));
        assertTrue(plain.contains("scan_orthographic_view  true"));
        assertTrue(plain.contains("replace_region_blocks  false"));
        assertTrue(plain.contains("fill_region  true"));
        assertTrue(plain.contains("set_blocks  false"));
        assertTrue(plain.contains("get_edit_history  true"));
        assertTrue(plain.contains("undo_edit  false"));
        assertTrue(plain.contains("LOGGING"));
        assertTrue(plain.contains("console-level  warning"));
        assertTrue(plain.contains("detail-file  logs/dirt-detail.%g.jsonl"));
        assertTrue(plain.contains("detail-file-max-bytes  2000000"));
        assertTrue(plain.contains("detail-file-retained-files  7"));
        assertTrue(plain.contains("max-request-bytes  262144"));
        assertTrue(plain.contains("max-region-volume  131072"));
        assertTrue(plain.contains("max-touched-chunks  128"));
        assertTrue(plain.contains("max-inspection-touched-chunks  16"));
        assertTrue(plain.contains("max-block-state-patterns  32"));
        assertTrue(plain.contains("max-changed-blocks  65536"));
        assertTrue(plain.contains("max-inspection-volume  8192"));
        assertTrue(plain.contains("default-inspection-results  256"));
        assertTrue(plain.contains("max-inspection-results  1024"));
        assertTrue(plain.contains("EDIT HISTORY"));
        assertTrue(plain.contains("max-entries-per-world  10"));
        assertTrue(plain.contains("max-entries-total  50"));
        assertTrue(plain.contains("max-retained-changed-blocks  655360"));
        assertTrue(plain.contains("region-blocks-include-air  true"));
        assertTrue(plain.contains("region-blocks-format  runs"));
        assertTrue(plain.contains("edit-dry-run  true"));
    }

    @Test
    void toolsRendersEveryConfiguredStateAndClickablePurpose() throws Exception {
        var fixture = fixture(true, status());

        assertEquals(1, fixture.execute("dirt tools"));

        Component message = fixture.messages.getFirst();
        String plain = PLAIN.serialize(message);
        assertTrue(plain.contains("DIRT MCP  /  MCP Tools"));
        assertTrue(plain.contains("Paper startup snapshot  •  5 of 10 configured ON"));
        assertTrue(plain.contains("restart Paper, then the MCP host"));
        Set<String> expectedCommands = new HashSet<>();
        for (McpTool tool : McpTool.values()) {
            expectedCommands.add("/dirt tools " + tool.id());
        }
        assertEquals(expectedCommands, runCommands(message));
        DirtConfig activeConfig = config();
        for (McpTool tool : McpTool.values()) {
            McpToolHelp.ToolSpec spec = McpToolHelp.spec(tool);
            String state = activeConfig.tools().isEnabled(tool) ? "● ON   " : "○ OFF  ";
            assertEquals(
                    1, plain.lines().filter(line -> line.contains(tool.id())).count(), tool.id());
            assertTrue(plain.contains(state + tool.id()), tool.id());
            assertTrue(plain.contains(spec.purpose()), tool.id());
        }
    }

    @Test
    void everyToolHasACompleteDetailViewEvenWhenConfiguredOff() throws Exception {
        var fixture = fixture(true, status());
        DirtConfig activeConfig = config();

        for (McpTool tool : McpTool.values()) {
            assertEquals(1, fixture.execute("dirt tools " + tool.id()));

            McpToolHelp.ToolSpec spec = McpToolHelp.spec(tool);
            assertFalse(spec.title().isBlank());
            assertFalse(spec.purpose().isBlank());
            assertFalse(spec.arguments().isBlank());
            assertFalse(spec.returns().isBlank());
            assertFalse(spec.notes().isBlank());
            Component message = fixture.messages.getLast();
            String plain = PLAIN.serialize(message);
            assertTrue(plain.contains("DIRT MCP  /  MCP Tool"), tool.id());
            assertTrue(plain.contains(tool.id() + "  /  " + spec.title()), tool.id());
            assertTrue(
                    plain.contains(
                            activeConfig.tools().isEnabled(tool)
                                    ? "● CONFIGURED ON"
                                    : "○ CONFIGURED OFF"),
                    tool.id());
            assertTrue(plain.contains("Paper startup snapshot"), tool.id());
            assertTrue(plain.contains("Type  " + spec.kind().label()), tool.id());
            assertTrue(plain.contains("PURPOSE"), tool.id());
            assertTrue(plain.contains("ARGUMENTS"), tool.id());
            assertTrue(plain.contains("RETURNS"), tool.id());
            assertTrue(plain.contains("BEHAVIOR"), tool.id());
            assertTrue(plain.contains(spec.purpose()), tool.id());
            assertTrue(plain.contains(spec.arguments()), tool.id());
            assertTrue(plain.contains(spec.returns()), tool.id());
            assertTrue(plain.contains(spec.notes()), tool.id());
            assertTrue(plain.contains("Canonical results are in structuredContent"), tool.id());
            assertTrue(plain.contains("structuredContent.callId"), tool.id());
            assertTrue(plain.contains("structuredContent.error"), tool.id());
            assertTrue(plain.contains("code-specific details"), tool.id());
            assertEquals(Set.of("/dirt tools"), runCommands(message), tool.id());
        }
    }

    @Test
    void permissionControlsVisibilityExecutionAndSubcommandSuggestions() throws Exception {
        var allowed = fixture(true, status());
        var denied = fixture(false, status());

        assertEquals(
                Set.of("config", "help", "status", "tools", "version"),
                allowed.suggestions("dirt "));
        Set<String> toolSuggestions = new HashSet<>();
        for (McpTool tool : McpTool.values()) {
            toolSuggestions.add(tool.id());
        }
        assertEquals(toolSuggestions, allowed.suggestions("dirt tools "));
        assertFalse(denied.canUseRoot());
        assertThrows(CommandSyntaxException.class, () -> denied.execute("dirt"));
        assertThrows(CommandSyntaxException.class, () -> allowed.execute("dirt unknown"));
        assertThrows(CommandSyntaxException.class, () -> allowed.execute("dirt tools not_a_tool"));
        assertTrue(denied.messages.isEmpty());
    }

    @Test
    void pluginMetadataMakesPermissionOperatorOnlyByDefault() {
        YamlConfiguration metadata = pluginMetadata();

        assertEquals(
                "op",
                metadata.getString("permissions." + DirtAdminCommand.PERMISSION + ".default"));
        assertTrue(
                metadata.getString("permissions." + DirtAdminCommand.PERMISSION + ".description")
                        .contains("tool catalog"));
    }

    private static CommandFixture fixture(boolean allowed, GetServerStatus status) {
        return fixture(allowed, command(status));
    }

    private static CommandFixture fixture(boolean allowed, DirtAdminCommand command) {
        return new CommandFixture(allowed, command);
    }

    private static DirtAdminCommand command(GetServerStatus status) {
        return command(
                status,
                DirtLog.consoleOnly(NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR));
    }

    private static DirtAdminCommand command(GetServerStatus status, DirtLog log) {
        return new DirtAdminCommand("DirtMCP", "0.1.0-SNAPSHOT", config(), status, log);
    }

    private static DirtLog recordingLog(List<LogRecord> records) {
        Handler handler =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        records.add(record);
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        return DirtLog.withDetailHandler(
                NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR, handler);
    }

    private static DirtConfig config() {
        return new DirtConfig(
                new DirtConfig.Bridge(8_765, 5, 6, 30, 2),
                new DirtConfig.Tools(
                        Set.of(
                                McpTool.PING_SERVER,
                                McpTool.COUNT_REGION_BLOCK_STATES,
                                McpTool.SCAN_ORTHOGRAPHIC_VIEW,
                                McpTool.FILL_REGION,
                                McpTool.GET_EDIT_HISTORY)),
                new DirtConfig.Logging(DirtConfig.ConsoleLogLevel.WARNING, 2_000_000, 7),
                new DirtConfig.Limits(262_144, 131_072, 128, 16, 32, 65_536, 8_192, 256, 1_024),
                new DirtConfig.EditHistory(10, 50, 655_360),
                new DirtConfig.Defaults(true, "runs", true));
    }

    private static GetServerStatus status() {
        GetServerStatus.Result result =
                new GetServerStatus.Result(
                        new GetServerStatus.Builds(
                                "26.2", "Paper build 112", "0.1.0-SNAPSHOT", "2.15.4"),
                        new GetServerStatus.Performance(19.95, 4.25),
                        new GetServerStatus.PlayerSummary(
                                1,
                                20,
                                List.of(
                                        new GetServerStatus.OnlinePlayer(
                                                "Builder",
                                                "world",
                                                "creative",
                                                "north",
                                                new BlockPosition(1, 64, 2)))),
                        List.of(
                                new GetServerStatus.WorldStatus(
                                        "world",
                                        "normal",
                                        -64,
                                        319,
                                        new BlockPosition(0, 64, 0),
                                        6_000,
                                        false,
                                        false,
                                        1)),
                        config().tools().flags(),
                        new GetServerStatus.EffectiveLogging("warning", 2_000_000, 7),
                        new GetServerStatus.EffectiveLimits(1, 1, 1, 1, 1, 1, 1, 1, 1),
                        new GetServerStatus.EffectiveEditHistory(2, 3, 4),
                        new GetServerStatus.EffectiveDefaults(false, "blocks", false));
        return () -> result;
    }

    private static YamlConfiguration pluginMetadata() {
        var stream = DirtAdminCommandTest.class.getResourceAsStream("/plugin.yml");
        if (stream == null) {
            throw new IllegalStateException("Packaged plugin.yml is unavailable");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read packaged plugin.yml", exception);
        }
    }

    private static boolean containsClickEvent(Component component) {
        if (component.clickEvent() != null) {
            return true;
        }
        return component.children().stream().anyMatch(DirtAdminCommandTest::containsClickEvent);
    }

    private static Set<String> runCommands(Component component) {
        Set<String> commands = new HashSet<>();
        ClickEvent<?> clickEvent = component.clickEvent();
        if (clickEvent != null
                && clickEvent.action() == ClickEvent.Action.RUN_COMMAND
                && clickEvent.payload() instanceof ClickEvent.Payload.Text text) {
            commands.add(text.value());
        }
        for (Component child : component.children()) {
            commands.addAll(runCommands(child));
        }
        return commands;
    }

    private static final class CommandFixture {
        private final List<Component> messages = new ArrayList<>();
        private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        private final CommandSourceStack source;

        private CommandFixture(boolean allowed, DirtAdminCommand command) {
            this.source = new TestSource(sender(allowed, this.messages));
            this.dispatcher.getRoot().addChild(command.command());
        }

        private int execute(String input) throws CommandSyntaxException {
            return this.dispatcher.execute(input, this.source);
        }

        private Set<String> suggestions(String input) {
            return this.dispatcher
                    .getCompletionSuggestions(this.dispatcher.parse(input, this.source))
                    .join()
                    .getList()
                    .stream()
                    .map(suggestion -> suggestion.getText())
                    .collect(HashSet::new, Set::add, Set::addAll);
        }

        private boolean canUseRoot() {
            return this.dispatcher.getRoot().getChild("dirt").canUse(this.source);
        }
    }

    private record TestSource(CommandSender sender) implements CommandSourceStack {
        @Override
        public Location getLocation() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandSender getSender() {
            return this.sender;
        }

        @Override
        public Entity getExecutor() {
            return null;
        }

        @Override
        public Player getPlayerOrThrow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entity getEntityOrThrow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandSourceStack withLocation(Location location) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandSourceStack withExecutor(Entity executor) {
            throw new UnsupportedOperationException();
        }
    }

    private static CommandSender sender(boolean allowed, List<Component> messages) {
        return (CommandSender)
                Proxy.newProxyInstance(
                        DirtAdminCommandTest.class.getClassLoader(),
                        new Class<?>[] {CommandSender.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("hasPermission")) {
                                return allowed;
                            }
                            if (method.getName().equals("sendMessage")
                                    && arguments != null
                                    && arguments.length == 1
                                    && arguments[0] instanceof Component component) {
                                messages.add(component);
                                return null;
                            }
                            if (method.getName().equals("getName")) {
                                return "Test sender";
                            }
                            if (method.getName().equals("isOp")) {
                                return allowed;
                            }
                            if (method.getName().equals("toString")) {
                                return "TestCommandSender";
                            }
                            if (method.getReturnType().equals(boolean.class)) {
                                return false;
                            }
                            if (method.getReturnType().equals(int.class)) {
                                return 0;
                            }
                            return null;
                        });
    }
}
