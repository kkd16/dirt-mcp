package ca.deliyannides.dirtmcp.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.access.AccessControl;
import ca.deliyannides.dirtmcp.paper.bridge.BridgeOperation;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus;
import ca.deliyannides.dirtmcp.paper.world.model.BlockPosition;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

final class DirtAdminCommandTest {
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();
    private static final UUID CALL_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final UUID PLAYER_ID = UUID.fromString("223e4567-e89b-42d3-a456-426614174000");
    private static final Instant CREATED_AT = Instant.parse("2026-08-30T12:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-30T12:10:00Z");

    @Test
    void helpOnlyExposesCommandsAvailableToTheSender() throws Exception {
        CommandFixture operator = fixture(new SenderAccess(true, true, true, true), status());
        CommandFixture linker = fixture(new SenderAccess(false, false, true, true), status());
        CommandFixture unprivileged =
                fixture(new SenderAccess(false, false, false, false), status());

        assertEquals(
                Set.of(
                        "config", "help", "invite", "invites", "link", "status", "tools", "user",
                        "users", "version"),
                operator.suggestions("dirt "));
        assertEquals(1, operator.execute("dirt"));
        assertEquals(1, linker.execute("dirt help"));
        assertEquals(1, unprivileged.execute("dirt"));
        for (String command :
                List.of(
                        "/dirt users",
                        "/dirt invites",
                        "/dirt invite create <player> <profile>",
                        "/dirt invite revoke <id>",
                        "/dirt user disable <username|id>",
                        "/dirt user enable <username|id>",
                        "/dirt user recover <username|id>",
                        "/dirt user unlink <username|id>",
                        "/dirt user access <username|id> <profile>",
                        "/dirt tools")) {
            assertTrue(operator.lastPlainMessage().contains(command));
        }
        assertTrue(operator.lastPlainMessage().contains("/dirt link"));
        assertFalse(linker.lastPlainMessage().contains("/dirt users"));
        assertFalse(linker.lastPlainMessage().contains("/dirt invites"));
        assertFalse(linker.lastPlainMessage().contains("/dirt invite create"));
        assertFalse(linker.lastPlainMessage().contains("/dirt user disable"));
        assertTrue(linker.lastPlainMessage().contains("/dirt link"));
        assertFalse(unprivileged.lastPlainMessage().contains("/dirt link"));
        assertFalse(unprivileged.lastPlainMessage().contains("/dirt status"));
        assertThrows(CommandSyntaxException.class, () -> operator.execute("dirt access users"));
        assertThrows(CommandSyntaxException.class, () -> operator.execute("dirt invitations"));
    }

    @Test
    void adminCommandsRequireOperatorStatusEvenWhenPermissionWasGranted() {
        CommandFixture nonOperator = fixture(new SenderAccess(false, true, false, false), status());

        assertThrows(CommandSyntaxException.class, () -> nonOperator.execute("dirt status"));
        assertThrows(CommandSyntaxException.class, () -> nonOperator.execute("dirt users"));
    }

    @Test
    void administratorsCanInspectDiagnostics() throws Exception {
        AtomicReference<GetServerStatus.Request> captured = new AtomicReference<>();
        GetServerStatus delegate = status();
        CommandFixture fixture =
                fixture(
                        new SenderAccess(true, true, false, false),
                        request -> {
                            captured.set(request);
                            return delegate.getStatus(request);
                        });

        assertEquals(1, fixture.execute("dirt version"));
        assertTrue(fixture.lastPlainMessage().contains("Version  0.1.0"));
        assertEquals(1, fixture.execute("dirt status"));
        assertEquals(new GetServerStatus.Request(true, true, false), captured.get());
        assertTrue(fixture.lastPlainMessage().contains("Bridge  127.0.0.1:8765"));
        assertTrue(fixture.lastPlainMessage().contains("Access  http://127.0.0.1:3000"));
        assertTrue(fixture.lastPlainMessage().contains("Paper  Paper build 121"));
        assertEquals(1, fixture.execute("dirt config"));
        assertTrue(fixture.lastPlainMessage().contains("connect-timeout-millis  2000"));
        assertTrue(
                fixture.lastPlainMessage().contains("allowed-operations  [pingServer, setBlocks]"));
        assertFalse(
                fixture.lastPlainMessage().toLowerCase(java.util.Locale.ROOT).contains("token"));
    }

    @Test
    void statusReportsExpectedOperationalFailureWithoutThrowing() throws Exception {
        OperationException expected =
                new OperationException(
                        OperationFailure.SERVER_UNAVAILABLE,
                        "FAWE is not available",
                        new ErrorDetails.ServerUnavailable.DependencyUnavailable());
        CommandFixture fixture =
                fixture(
                        new SenderAccess(true, true, false, false),
                        request -> {
                            throw expected;
                        });

        assertEquals(0, fixture.execute("dirt status"));
        assertTrue(fixture.lastPlainMessage().contains("FAWE is not available"));
    }

    @Test
    void userAndInvitationPagesRenderAndMarshalCompletionThroughMainThread() throws Exception {
        StubAccessControl access = new StubAccessControl();
        DirectMainThread mainThread = new DirectMainThread();
        access.users =
                CompletableFuture.completedFuture(
                        new AccessControl.UserPage(
                                CALL_ID,
                                2,
                                20,
                                21,
                                2,
                                List.of(
                                        new AccessControl.UserSummary(
                                                "usr_1",
                                                "Builder",
                                                AccessControl.UserStatus.ACTIVE,
                                                AccessControl.AccessProfile.BUILDER,
                                                PLAYER_ID,
                                                CREATED_AT))));
        access.invitations =
                CompletableFuture.completedFuture(
                        new AccessControl.InvitationPage(
                                CALL_ID, 1, 20, 1, 1, List.of(invitation("invite_1"))));
        CommandFixture fixture =
                fixture(new SenderAccess(true, true, false, false), status(), access, mainThread);

        assertEquals(1, fixture.execute("dirt users 2"));
        assertEquals(2, access.requestedUsersPage);
        assertTrue(fixture.lastPlainMessage().contains("Builder  active"));
        assertTrue(fixture.lastPlainMessage().contains("UUID: " + PLAYER_ID));
        assertEquals(1, fixture.execute("dirt invites"));
        assertEquals(1, access.requestedInvitationsPage);
        assertTrue(fixture.lastPlainMessage().contains("invite_1  pending"));
        assertTrue(hasCopyValue(fixture.lastMessage(), "invite_1"));
        assertEquals(2, mainThread.calls);
    }

    @Test
    void anOutOfRangePageOffersOneHopBackToTheLastAvailablePage() throws Exception {
        StubAccessControl access = new StubAccessControl();
        access.users =
                CompletableFuture.completedFuture(
                        new AccessControl.UserPage(CALL_ID, 999, 20, 21, 2, List.of()));
        CommandFixture fixture =
                fixture(new SenderAccess(true, true, false, false), status(), access);

        assertEquals(1, fixture.execute("dirt users 999"));
        assertTrue(fixture.lastPlainMessage().contains("‹ Last page"));
        assertTrue(hasRunCommand(fixture.lastMessage(), "/dirt users 2"));
    }

    @Test
    void nonSecretMutationsCallTheExpectedControlOperations() throws Exception {
        StubAccessControl access = new StubAccessControl();
        CommandFixture fixture =
                fixture(new SenderAccess(true, true, false, false), status(), access);

        assertEquals(1, fixture.execute("dirt invite revoke invite_7"));
        assertEquals("invite_7", access.revokedInvitation);
        assertEquals(1, fixture.execute("dirt user disable builder"));
        assertEquals("builder", access.disabledUser);
        assertEquals(1, fixture.execute("dirt user enable builder"));
        assertEquals("builder", access.enabledUser);
        assertEquals(1, fixture.execute("dirt user unlink builder"));
        assertEquals("builder", access.unlinkedUser);
        assertTrue(fixture.lastPlainMessage().contains("Builder was unlinked"));
        assertEquals(1, fixture.execute("dirt user access builder viewer"));
        assertEquals("builder", access.accessUser);
        assertEquals(AccessControl.AccessProfile.VIEWER, access.changedAccessProfile);
        assertTrue(fixture.lastPlainMessage().contains("Viewer"));
    }

    @Test
    void invitationIsSentOnlyToTheNamedPlayersChat() throws Exception {
        StubAccessControl consoleAccess = new StubAccessControl();
        CommandFixture console =
                fixture(new SenderAccess(true, true, false, false), status(), consoleAccess);

        assertTrue(console.suggestions("dirt invite create ").contains("Builder"));
        assertEquals(
                Set.of("viewer", "builder", "operator"),
                console.suggestions("dirt invite create Builder "));
        assertThrows(
                CommandSyntaxException.class, () -> console.execute("dirt invite create Builder"));
        assertEquals(1, console.execute("dirt invite create Builder builder"));
        assertEquals(1, consoleAccess.createdInvitations);
        assertEquals(AccessControl.AccessProfile.BUILDER, consoleAccess.createdAccessProfile);
        assertEquals(PLAYER_ID, consoleAccess.linkedUuid);
        assertEquals("Builder", consoleAccess.linkedName);
        assertTrue(console.lastPlainMessage().contains("Invitation sent privately to Builder"));
        assertFalse(console.messagesContain("https://dashboard.example/invite/secret"));
        assertTrue(
                console.lastTargetPlainMessage()
                        .contains("https://dashboard.example/invite/secret"));
        assertTrue(
                hasCopyValue(
                        console.lastTargetMessage(), "https://dashboard.example/invite/secret"));

        assertEquals(0, console.execute("dirt invite create Missing viewer"));
        assertTrue(console.lastPlainMessage().contains("must be online"));
    }

    @Test
    void toolFieldGuideShowsServerAndPersonalAccessWithDetails() throws Exception {
        StubAccessControl consoleAccess = new StubAccessControl();
        CommandFixture console =
                fixture(new SenderAccess(true, true, false, false), status(), consoleAccess);

        assertEquals(1, console.execute("dirt tools"));
        assertTrue(console.lastPlainMessage().contains("12 supported  •  2 enabled"));
        assertTrue(console.lastPlainMessage().contains("ping_server"));
        assertTrue(console.lastPlainMessage().contains("set_blocks"));
        assertTrue(hasRunCommand(console.lastMessage(), "/dirt tools set_blocks"));
        assertEquals(1, console.execute("dirt tools set_blocks"));
        assertTrue(console.lastPlainMessage().contains("INPUTS"));
        assertTrue(console.lastPlainMessage().contains("Changes world  yes"));
        assertEquals(0, console.execute("dirt tools not_a_tool"));
        assertTrue(console.lastPlainMessage().contains("not supported"));

        StubAccessControl playerAccess = new StubAccessControl();
        CommandFixture player =
                fixture(new SenderAccess(false, false, true, true), status(), playerAccess);
        assertEquals(1, player.execute("dirt tools"));
        assertEquals(PLAYER_ID, playerAccess.accountLookupUuid);
        assertTrue(player.lastPlainMessage().contains("2 available as Builder"));
        assertEquals(1, player.execute("dirt tools set_blocks"));
        assertTrue(player.lastPlainMessage().contains("Your access  available"));

        CommandFixture unprivileged =
                fixture(new SenderAccess(false, false, false, false), status());
        assertThrows(CommandSyntaxException.class, () -> unprivileged.execute("dirt tools"));
    }

    @Test
    void recoverySecretsRequireAnInGameOperatorAndAreClickToCopy() throws Exception {
        StubAccessControl consoleAccess = new StubAccessControl();
        CommandFixture console =
                fixture(new SenderAccess(true, true, false, false), status(), consoleAccess);

        assertEquals(0, console.execute("dirt user recover Builder"));
        assertEquals(null, consoleAccess.recoveredUser);
        assertTrue(console.lastPlainMessage().contains("run in-game by an operator"));

        StubAccessControl playerAccess = new StubAccessControl();
        CommandFixture player =
                fixture(new SenderAccess(true, true, false, true), status(), playerAccess);
        assertEquals(1, player.execute("dirt user recover Builder"));
        assertEquals("Builder", playerAccess.recoveredUser);
        assertTrue(player.lastPlainMessage().contains("https://dashboard.example/recover/secret"));
        assertTrue(hasCopyValue(player.lastMessage(), "https://dashboard.example/recover/secret"));
    }

    @Test
    void linkIsPlayerOnlyAndUsesTheAuthenticatedOnlineIdentity() throws Exception {
        StubAccessControl consoleAccess = new StubAccessControl();
        CommandFixture console =
                fixture(new SenderAccess(true, false, true, false), status(), consoleAccess);

        assertEquals(0, console.execute("dirt link"));
        assertEquals(null, consoleAccess.linkedUuid);
        assertTrue(console.lastPlainMessage().contains("in-game player"));
        assertEquals(1, console.execute("dirt help"));
        assertFalse(console.lastPlainMessage().contains("/dirt link"));

        StubAccessControl playerAccess = new StubAccessControl();
        CommandFixture player =
                fixture(new SenderAccess(false, false, true, true), status(), playerAccess);
        assertEquals(1, player.execute("dirt link"));
        assertEquals(PLAYER_ID, playerAccess.linkedUuid);
        assertEquals("Builder", playerAccess.linkedName);
        assertTrue(player.lastPlainMessage().contains("LINK-1234"));
        assertTrue(hasCopyValue(player.lastMessage(), "LINK-1234"));
        assertTrue(hasCopyValue(player.lastMessage(), "https://dashboard.example/link/secret"));
    }

    @Test
    void asynchronousFailuresNeverEchoUntrustedDetails() throws Exception {
        StubAccessControl access = new StubAccessControl();
        access.users =
                CompletableFuture.failedFuture(new RuntimeException("private-server-detail"));
        CommandFixture fixture =
                fixture(new SenderAccess(true, true, false, false), status(), access);

        assertEquals(1, fixture.execute("dirt users"));
        assertTrue(fixture.lastPlainMessage().contains("dashboard access request failed"));
        assertFalse(fixture.lastPlainMessage().contains("private-server-detail"));
    }

    @Test
    void pluginMetadataDeclaresAccessPermissions() {
        YamlConfiguration metadata = pluginMetadata();

        assertTrue(metadata.contains("permissions." + DirtAdminCommand.ADMIN_PERMISSION));
        assertTrue(metadata.contains("permissions." + DirtAdminCommand.LINK_PERMISSION));
        assertEquals(
                "op",
                metadata.getString(
                        "permissions." + DirtAdminCommand.ADMIN_PERMISSION + ".default"));
        assertEquals(
                "true",
                metadata.getString("permissions." + DirtAdminCommand.LINK_PERMISSION + ".default"));
    }

    private static CommandFixture fixture(SenderAccess senderAccess, GetServerStatus status) {
        return fixture(senderAccess, status, new StubAccessControl());
    }

    private static CommandFixture fixture(
            SenderAccess senderAccess, GetServerStatus status, StubAccessControl access) {
        return fixture(senderAccess, status, access, new DirectMainThread());
    }

    private static CommandFixture fixture(
            SenderAccess senderAccess,
            GetServerStatus status,
            StubAccessControl access,
            DirectMainThread mainThread) {
        DirtLog log = DirtLog.consoleOnly(NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR);
        ServerFixture server = testServer();
        DirtAdminCommand command =
                new DirtAdminCommand(
                        "DirtMCP",
                        "0.1.0",
                        server.server(),
                        config(),
                        status,
                        access,
                        mainThread,
                        log);
        return new CommandFixture(senderAccess, command, server.targetMessages());
    }

    private static DirtConfig config() {
        return new DirtConfig(
                new DirtConfig.Bridge(
                        8_765,
                        5,
                        6,
                        30,
                        2,
                        262_144,
                        List.of(BridgeOperation.PING_SERVER, BridgeOperation.SET_BLOCKS)),
                new DirtConfig.AccessControl("http://127.0.0.1:3000", 2_000, 5_000),
                new DirtConfig.Logging(DirtConfig.ConsoleLogLevel.WARNING, 2_000_000, 7),
                new DirtConfig.Limits(
                        131_072, 128, 16, 32, 32, 64, 65_536, 8_192, 16_384, 1_024, 512, 10, 8_192),
                new DirtConfig.EditHistory(10, 50, 655_360));
    }

    private static GetServerStatus status() {
        GetServerStatus.Result result =
                new GetServerStatus.Result(
                        new GetServerStatus.Builds("26.2", "Paper build 121", "0.1.0", "2.15.4"),
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
                        null);
        return request -> result;
    }

    private static AccessControl.UserSummary user() {
        return new AccessControl.UserSummary(
                "usr_1",
                "Builder",
                AccessControl.UserStatus.ACTIVE,
                AccessControl.AccessProfile.BUILDER,
                PLAYER_ID,
                CREATED_AT);
    }

    private static AccessControl.InvitationSummary invitation(String id) {
        return new AccessControl.InvitationSummary(
                id,
                AccessControl.InvitationStatus.PENDING,
                AccessControl.AccessProfile.BUILDER,
                new AccessControl.MinecraftAccount(PLAYER_ID, "Builder"),
                CREATED_AT,
                EXPIRES_AT);
    }

    private static boolean hasCopyValue(Component component, String value) {
        ClickEvent<?> click = component.clickEvent();
        if (click != null
                && click.action() == ClickEvent.Action.COPY_TO_CLIPBOARD
                && click.payload() instanceof ClickEvent.Payload.Text text
                && text.value().equals(value)) {
            return true;
        }
        return component.children().stream().anyMatch(child -> hasCopyValue(child, value));
    }

    private static boolean hasRunCommand(Component component, String value) {
        ClickEvent<?> click = component.clickEvent();
        if (click != null
                && click.action() == ClickEvent.Action.RUN_COMMAND
                && click.payload() instanceof ClickEvent.Payload.Text text
                && text.value().equals(value)) {
            return true;
        }
        return component.children().stream().anyMatch(child -> hasRunCommand(child, value));
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

    private static final class CommandFixture {
        private final List<Component> messages = new ArrayList<>();
        private final List<Component> targetMessages;
        private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        private final CommandSourceStack source;

        private CommandFixture(
                SenderAccess access, DirtAdminCommand command, List<Component> targetMessages) {
            this.targetMessages = targetMessages;
            this.source = new TestSource(sender(access, this.messages));
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

        private Component lastMessage() {
            return this.messages.getLast();
        }

        private String lastPlainMessage() {
            return PLAIN.serialize(lastMessage());
        }

        private Component lastTargetMessage() {
            return this.targetMessages.getLast();
        }

        private String lastTargetPlainMessage() {
            return PLAIN.serialize(lastTargetMessage());
        }

        private boolean messagesContain(String value) {
            return this.messages.stream()
                    .map(PLAIN::serialize)
                    .anyMatch(text -> text.contains(value));
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

    private record SenderAccess(
            boolean operator, boolean adminPermission, boolean linkPermission, boolean player) {}

    private record ServerFixture(Server server, List<Component> targetMessages) {}

    private static ServerFixture testServer() {
        List<Component> targetMessages = new ArrayList<>();
        Player target = (Player) sender(new SenderAccess(false, false, true, true), targetMessages);
        Server server =
                (Server)
                        Proxy.newProxyInstance(
                                DirtAdminCommandTest.class.getClassLoader(),
                                new Class<?>[] {Server.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("getPlayerExact")) {
                                        return "Builder".equals(arguments[0]) ? target : null;
                                    }
                                    if (method.getName().equals("getPlayer")) {
                                        return PLAYER_ID.equals(arguments[0]) ? target : null;
                                    }
                                    if (method.getName().equals("getOnlinePlayers")) {
                                        return List.of(target);
                                    }
                                    if (method.getName().equals("toString")) {
                                        return "TestServer";
                                    }
                                    if (method.getReturnType().equals(boolean.class)) {
                                        return false;
                                    }
                                    if (method.getReturnType().equals(int.class)) {
                                        return 0;
                                    }
                                    return null;
                                });
        return new ServerFixture(server, targetMessages);
    }

    private static CommandSender sender(SenderAccess access, List<Component> messages) {
        Class<?> senderType = access.player() ? Player.class : CommandSender.class;
        return (CommandSender)
                Proxy.newProxyInstance(
                        DirtAdminCommandTest.class.getClassLoader(),
                        new Class<?>[] {senderType},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("hasPermission")) {
                                String permission = (String) arguments[0];
                                return switch (permission) {
                                    case DirtAdminCommand.ADMIN_PERMISSION ->
                                            access.adminPermission();
                                    case DirtAdminCommand.LINK_PERMISSION ->
                                            access.linkPermission();
                                    default -> false;
                                };
                            }
                            if (method.getName().equals("sendMessage")
                                    && arguments != null
                                    && arguments.length == 1
                                    && arguments[0] instanceof Component component) {
                                messages.add(component);
                                return null;
                            }
                            if (method.getName().equals("getUniqueId")) {
                                return PLAYER_ID;
                            }
                            if (method.getName().equals("getName")) {
                                return "Builder";
                            }
                            if (method.getName().equals("isOp")) {
                                return access.operator();
                            }
                            if (method.getName().equals("isOnline")) {
                                return true;
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
                            if (method.getReturnType().equals(long.class)) {
                                return 0L;
                            }
                            if (method.getReturnType().equals(double.class)) {
                                return 0.0D;
                            }
                            if (method.getReturnType().equals(float.class)) {
                                return 0.0F;
                            }
                            return null;
                        });
    }

    private static final class DirectMainThread implements MainThread {
        private int calls;

        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            this.calls++;
            try {
                return action.get();
            } catch (Exception exception) {
                throw new PaperMainThreadException("test action failed", exception);
            }
        }

        @Override
        public void close() {}
    }

    private static final class StubAccessControl implements AccessControl {
        private CompletionStage<UserPage> users =
                CompletableFuture.completedFuture(new UserPage(CALL_ID, 1, 20, 0, 0, List.of()));
        private CompletionStage<InvitationPage> invitations =
                CompletableFuture.completedFuture(
                        new InvitationPage(CALL_ID, 1, 20, 0, 0, List.of()));
        private int requestedUsersPage;
        private int requestedInvitationsPage;
        private int createdInvitations;
        private AccessProfile createdAccessProfile;
        private String revokedInvitation;
        private String disabledUser;
        private String enabledUser;
        private String recoveredUser;
        private String unlinkedUser;
        private String accessUser;
        private AccessProfile changedAccessProfile;
        private UUID accountLookupUuid;
        private UUID linkedUuid;
        private String linkedName;

        @Override
        public CompletionStage<UserPage> listUsers(int page) {
            this.requestedUsersPage = page;
            return this.users;
        }

        @Override
        public CompletionStage<InvitationPage> listInvitations(int page) {
            this.requestedInvitationsPage = page;
            return this.invitations;
        }

        @Override
        public CompletionStage<CreateInvitationResult> createInvitation(
                UUID minecraftUuid, String minecraftName, AccessProfile accessProfile) {
            this.createdInvitations++;
            this.createdAccessProfile = accessProfile;
            this.linkedUuid = minecraftUuid;
            this.linkedName = minecraftName;
            return CompletableFuture.completedFuture(
                    new CreateInvitationResult(
                            CALL_ID,
                            invitation("invite_secret"),
                            URI.create("https://dashboard.example/invite/secret")));
        }

        @Override
        public CompletionStage<InvitationMutationResult> revokeInvitation(String id) {
            this.revokedInvitation = id;
            return CompletableFuture.completedFuture(
                    new InvitationMutationResult(CALL_ID, invitation(id)));
        }

        @Override
        public CompletionStage<UserMutationResult> disableUser(String selector) {
            this.disabledUser = selector;
            return CompletableFuture.completedFuture(new UserMutationResult(CALL_ID, user()));
        }

        @Override
        public CompletionStage<UserMutationResult> enableUser(String selector) {
            this.enabledUser = selector;
            return CompletableFuture.completedFuture(new UserMutationResult(CALL_ID, user()));
        }

        @Override
        public CompletionStage<UserRecoveryResult> createUserRecovery(String selector) {
            this.recoveredUser = selector;
            return CompletableFuture.completedFuture(
                    new UserRecoveryResult(
                            CALL_ID,
                            user(),
                            URI.create("https://dashboard.example/recover/secret"),
                            EXPIRES_AT));
        }

        @Override
        public CompletionStage<UserMutationResult> unlinkUser(String selector) {
            this.unlinkedUser = selector;
            return CompletableFuture.completedFuture(new UserMutationResult(CALL_ID, user()));
        }

        @Override
        public CompletionStage<UserMutationResult> setAccessProfile(
                String selector, AccessProfile accessProfile) {
            this.accessUser = selector;
            this.changedAccessProfile = accessProfile;
            return CompletableFuture.completedFuture(
                    new UserMutationResult(
                            CALL_ID,
                            new UserSummary(
                                    "usr_1",
                                    "Builder",
                                    UserStatus.ACTIVE,
                                    accessProfile,
                                    PLAYER_ID,
                                    CREATED_AT)));
        }

        @Override
        public CompletionStage<MinecraftAccountUserResult> findUserByMinecraftUuid(
                UUID minecraftUuid) {
            this.accountLookupUuid = minecraftUuid;
            return CompletableFuture.completedFuture(
                    new MinecraftAccountUserResult(CALL_ID, user()));
        }

        @Override
        public CompletionStage<MinecraftLinkChallenge> createMinecraftLinkChallenge(
                UUID minecraftUuid, String minecraftName) {
            this.linkedUuid = minecraftUuid;
            this.linkedName = minecraftName;
            return CompletableFuture.completedFuture(
                    new MinecraftLinkChallenge(
                            CALL_ID,
                            "LINK-1234",
                            URI.create("https://dashboard.example/link/secret"),
                            EXPIRES_AT));
        }

        @Override
        public void close() {}
    }
}
