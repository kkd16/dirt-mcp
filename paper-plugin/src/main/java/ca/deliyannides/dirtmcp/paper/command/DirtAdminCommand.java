package ca.deliyannides.dirtmcp.paper.command;

import ca.deliyannides.dirtmcp.paper.access.AccessControl;
import ca.deliyannides.dirtmcp.paper.access.AccessControlException;
import ca.deliyannides.dirtmcp.paper.catalog.ToolCatalog;
import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import ca.deliyannides.dirtmcp.paper.status.GetServerStatus;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Paper-facing command for operator administration and authenticated player linking. */
public final class DirtAdminCommand {
    public static final String ADMIN_PERMISSION = "dirtmcp.admin";
    public static final String LINK_PERMISSION = "dirtmcp.link";

    private static final TextColor ACCENT = TextColor.color(0x38BDF8);
    private static final TextColor SECONDARY_ACCENT = TextColor.color(0x22D3EE);
    private static final String DESCRIPTION = "Secure access to live Minecraft worlds";
    private static final DynamicCommandExceptionType INVALID_ACCESS_PROFILE =
            new DynamicCommandExceptionType(
                    value -> new LiteralMessage("Unknown access profile: " + value));

    private final String pluginName;
    private final String pluginVersion;
    private final Server server;
    private final DirtConfig config;
    private final GetServerStatus status;
    private final AccessControl access;
    private final MainThread mainThread;
    private final DirtLog log;
    private final ToolCatalog toolCatalog;

    public DirtAdminCommand(
            String pluginName,
            String pluginVersion,
            Server server,
            DirtConfig config,
            GetServerStatus status,
            AccessControl access,
            MainThread mainThread,
            DirtLog log) {
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        this.pluginVersion = Objects.requireNonNull(pluginVersion, "pluginVersion");
        this.server = Objects.requireNonNull(server, "server");
        this.config = Objects.requireNonNull(config, "config");
        this.status = Objects.requireNonNull(status, "status");
        this.access = Objects.requireNonNull(access, "access");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.log = Objects.requireNonNull(log, "log");
        this.toolCatalog = ToolCatalog.load();
    }

    public LiteralCommandNode<CommandSourceStack> command() {
        return Commands.literal("dirt")
                .executes(context -> showHelp(context.getSource().getSender()))
                .then(
                        Commands.literal("help")
                                .executes(context -> showHelp(context.getSource().getSender())))
                .then(
                        Commands.literal("version")
                                .requires(source -> hasAdminPermission(source.getSender()))
                                .executes(context -> showVersion(context.getSource().getSender())))
                .then(
                        Commands.literal("status")
                                .requires(source -> hasAdminPermission(source.getSender()))
                                .executes(context -> showStatus(context.getSource().getSender())))
                .then(
                        Commands.literal("config")
                                .requires(source -> hasAdminPermission(source.getSender()))
                                .executes(context -> showConfig(context.getSource().getSender())))
                .then(toolsCommand())
                .then(usersCommand())
                .then(invitesCommand())
                .then(inviteCommand())
                .then(userCommand())
                .then(
                        Commands.literal("link")
                                .requires(source -> hasLinkPermission(source.getSender()))
                                .executes(context -> link(context.getSource().getSender())))
                .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> usersCommand() {
        return Commands.literal("users")
                .requires(source -> hasAdminPermission(source.getSender()))
                .then(
                        Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(
                                        context ->
                                                listUsers(
                                                        context.getSource().getSender(),
                                                        IntegerArgumentType.getInteger(
                                                                context, "page"))))
                .executes(context -> listUsers(context.getSource().getSender(), 1));
    }

    private LiteralArgumentBuilder<CommandSourceStack> toolsCommand() {
        return Commands.literal("tools")
                .requires(source -> canBrowseTools(source.getSender()))
                .then(
                        Commands.argument("tool", StringArgumentType.word())
                                .suggests(
                                        (context, builder) -> {
                                            for (ToolCatalog.Entry entry :
                                                    this.toolCatalog.entries()) {
                                                builder.suggest(entry.name());
                                            }
                                            return builder.buildFuture();
                                        })
                                .executes(
                                        context ->
                                                showTools(
                                                        context.getSource().getSender(),
                                                        StringArgumentType.getString(
                                                                context, "tool"))))
                .executes(context -> showTools(context.getSource().getSender(), null));
    }

    private LiteralArgumentBuilder<CommandSourceStack> invitesCommand() {
        return Commands.literal("invites")
                .requires(source -> hasAdminPermission(source.getSender()))
                .then(
                        Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(
                                        context ->
                                                listInvitations(
                                                        context.getSource().getSender(),
                                                        IntegerArgumentType.getInteger(
                                                                context, "page"))))
                .executes(context -> listInvitations(context.getSource().getSender(), 1));
    }

    private LiteralArgumentBuilder<CommandSourceStack> inviteCommand() {
        return Commands.literal("invite")
                .requires(source -> hasAdminPermission(source.getSender()))
                .then(
                        Commands.literal("create")
                                .then(
                                        Commands.argument("player", StringArgumentType.word())
                                                .suggests(
                                                        (context, builder) -> {
                                                            for (Player player :
                                                                    this.server
                                                                            .getOnlinePlayers()) {
                                                                builder.suggest(player.getName());
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                .then(
                                                        Commands.argument(
                                                                        "profile",
                                                                        StringArgumentType.word())
                                                                .suggests(
                                                                        (context, builder) -> {
                                                                            for (AccessControl
                                                                                            .AccessProfile
                                                                                    profile :
                                                                                            AccessControl
                                                                                                    .AccessProfile
                                                                                                    .values()) {
                                                                                builder.suggest(
                                                                                        profile
                                                                                                .wireName());
                                                                            }
                                                                            return builder
                                                                                    .buildFuture();
                                                                        })
                                                                .executes(
                                                                        context ->
                                                                                createInvitation(
                                                                                        context.getSource()
                                                                                                .getSender(),
                                                                                        StringArgumentType
                                                                                                .getString(
                                                                                                        context,
                                                                                                        "player"),
                                                                                        accessProfile(
                                                                                                StringArgumentType
                                                                                                        .getString(
                                                                                                                context,
                                                                                                                "profile")))))))
                .then(
                        Commands.literal("revoke")
                                .then(
                                        Commands.argument("id", StringArgumentType.word())
                                                .executes(
                                                        context ->
                                                                revokeInvitation(
                                                                        context.getSource()
                                                                                .getSender(),
                                                                        StringArgumentType
                                                                                .getString(
                                                                                        context,
                                                                                        "id")))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> userCommand() {
        LiteralArgumentBuilder<CommandSourceStack> user =
                Commands.literal("user").requires(source -> hasAdminPermission(source.getSender()));
        user.then(userAction("disable", this.access::disableUser, "disabled"));
        user.then(userAction("enable", this.access::enableUser, "enabled"));
        user.then(
                Commands.literal("recover")
                        .then(
                                Commands.argument("selector", StringArgumentType.word())
                                        .executes(
                                                context ->
                                                        createUserRecovery(
                                                                context.getSource().getSender(),
                                                                StringArgumentType.getString(
                                                                        context, "selector")))));
        user.then(userAction("unlink", this.access::unlinkUser, "unlinked"));
        user.then(
                Commands.literal("access")
                        .then(
                                Commands.argument("selector", StringArgumentType.word())
                                        .then(
                                                Commands.argument(
                                                                "profile",
                                                                StringArgumentType.word())
                                                        .suggests(
                                                                (context, builder) -> {
                                                                    for (AccessControl.AccessProfile
                                                                            profile :
                                                                                    AccessControl
                                                                                            .AccessProfile
                                                                                            .values()) {
                                                                        builder.suggest(
                                                                                profile.wireName());
                                                                    }
                                                                    return builder.buildFuture();
                                                                })
                                                        .executes(
                                                                context -> {
                                                                    String selector =
                                                                            StringArgumentType
                                                                                    .getString(
                                                                                            context,
                                                                                            "selector");
                                                                    AccessControl.AccessProfile
                                                                            profile =
                                                                                    accessProfile(
                                                                                            StringArgumentType
                                                                                                    .getString(
                                                                                                            context,
                                                                                                            "profile"));
                                                                    return runAsync(
                                                                            context.getSource()
                                                                                    .getSender(),
                                                                            () ->
                                                                                    this.access
                                                                                            .setAccessProfile(
                                                                                                    selector,
                                                                                                    profile),
                                                                            result ->
                                                                                    accessProfileMessage(
                                                                                            result
                                                                                                    .user()));
                                                                }))));
        return user;
    }

    private LiteralArgumentBuilder<CommandSourceStack> userAction(
            String command,
            Function<String, CompletionStage<AccessControl.UserMutationResult>> action,
            String completedAction) {
        return Commands.literal(command)
                .then(
                        Commands.argument("selector", StringArgumentType.word())
                                .executes(
                                        context -> {
                                            String selector =
                                                    StringArgumentType.getString(
                                                            context, "selector");
                                            return runAsync(
                                                    context.getSource().getSender(),
                                                    () -> action.apply(selector),
                                                    result ->
                                                            userMutationMessage(
                                                                    result.user(),
                                                                    completedAction));
                                        }));
    }

    private int showHelp(CommandSender sender) {
        TextComponent.Builder message = panel("Command Center");
        message.append(Component.newline());
        message.append(Component.text(DESCRIPTION, NamedTextColor.GRAY));
        message.append(Component.newline()).append(Component.newline());
        if (hasAdminPermission(sender)) {
            appendCommand(message, "/dirt users", "View dashboard users");
            appendCommand(message, "/dirt invites", "View dashboard invites");
            appendCommand(
                    message,
                    "/dirt invite create <player> <profile>",
                    "Invite with explicit access");
            appendCommand(message, "/dirt invite revoke <id>", "Revoke an invite");
            appendCommand(message, "/dirt user disable <username|id>", "Disable a user");
            appendCommand(message, "/dirt user enable <username|id>", "Enable a user");
            appendCommand(message, "/dirt user recover <username|id>", "Create a recovery link");
            appendCommand(message, "/dirt user unlink <username|id>", "Unlink a Minecraft account");
            appendCommand(
                    message,
                    "/dirt user access <username|id> <profile>",
                    "Assign Viewer, Builder, or Operator");
            appendCommand(message, "/dirt status", "View live server and bridge status");
            appendCommand(message, "/dirt config", "Inspect the active configuration");
            appendCommand(message, "/dirt version", "Show plugin version information");
        }
        if (sender instanceof Player && hasLinkPermission(sender)) {
            appendCommand(message, "/dirt link", "Link this Minecraft account privately");
        }
        if (canBrowseTools(sender)) {
            appendCommand(message, "/dirt tools", "Browse supported tools and your access");
        }
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
            result = this.status.getStatus(new GetServerStatus.Request(true, true, false));
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
        appendValue(message, "Access", this.config.accessControl().origin());
        appendValue(message, "Minecraft", result.builds().minecraft());
        appendValue(message, "Paper", result.builds().paper());
        appendValue(message, "Dirt MCP", result.builds().dirtPlugin());
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
        appendValue(message, "max-request-bytes", bridge.maxRequestBytes());
        appendValue(
                message,
                "allowed-operations",
                bridge.allowedOperations().stream()
                        .map(operation -> operation.operationId())
                        .toList());

        DirtConfig.AccessControl accessControl = this.config.accessControl();
        appendSection(message, "Access Control");
        appendValue(message, "url", accessControl.origin());
        appendValue(message, "connect-timeout-millis", accessControl.connectTimeoutMillis());
        appendValue(message, "request-timeout-millis", accessControl.requestTimeoutMillis());

        DirtConfig.Logging logging = this.config.logging();
        appendSection(message, "Logging");
        appendValue(message, "console-level", logging.consoleLevel().configName());
        appendValue(message, "detail-file", DirtLog.DETAIL_FILE_PATTERN);
        appendValue(message, "detail-file-max-bytes", logging.detailFileMaxBytes());
        appendValue(message, "detail-file-retained-files", logging.detailFileRetainedFiles());

        DirtConfig.Limits limits = this.config.limits();
        appendSection(message, "Limits");
        appendValue(message, "max-region-volume", limits.maxRegionVolume());
        appendValue(message, "max-edit-touched-chunks", limits.maxEditTouchedChunks());
        appendValue(message, "max-inspection-touched-chunks", limits.maxInspectionTouchedChunks());
        appendValue(
                message, "max-perspective-touched-chunks", limits.maxPerspectiveTouchedChunks());
        appendValue(message, "max-block-state-patterns", limits.maxBlockStatePatterns());
        appendValue(message, "max-palette-entries", limits.maxPaletteEntries());
        appendValue(message, "max-changed-blocks", limits.maxChangedBlocks());
        appendValue(message, "max-inspection-volume", limits.maxInspectionVolume());
        appendValue(
                message,
                "max-perspective-ray-distance-budget",
                limits.maxPerspectiveRayDistanceBudget());
        appendValue(message, "max-inspection-results", limits.maxInspectionResultLimit());
        appendValue(message, "max-perspective-rays", limits.maxPerspectiveRays());
        appendValue(message, "max-commands-per-request", limits.maxCommandsPerRequest());
        appendValue(
                message, "max-command-feedback-characters", limits.maxCommandFeedbackCharacters());

        DirtConfig.EditHistory editHistory = this.config.editHistory();
        appendSection(message, "Edit History");
        appendValue(message, "max-entries-per-world", editHistory.maxEntriesPerWorld());
        appendValue(message, "max-entries-total", editHistory.maxEntriesTotal());
        appendValue(message, "max-retained-changed-blocks", editHistory.maxRetainedChangedBlocks());

        sender.sendMessage(message.build());
        return Command.SINGLE_SUCCESS;
    }

    private int listUsers(CommandSender sender, int page) {
        return runAsync(sender, () -> this.access.listUsers(page), this::usersMessage);
    }

    private int showTools(CommandSender sender, String requestedTool) {
        ToolCatalog.Entry selected =
                requestedTool == null ? null : this.toolCatalog.byName(requestedTool);
        if (requestedTool != null && selected == null) {
            sender.sendMessage(failureMessage("That Dirt tool is not supported."));
            return 0;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(toolsMessage(selected, null, PersonalAccessState.NOT_APPLICABLE));
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage(progressMessage());
        final CompletionStage<AccessControl.MinecraftAccountUserResult> pending;
        try {
            pending = this.access.findUserByMinecraftUuid(player.getUniqueId());
        } catch (RuntimeException failure) {
            deliver(sender, toolsMessage(selected, null, PersonalAccessState.UNKNOWN));
            return Command.SINGLE_SUCCESS;
        }
        pending.whenComplete(
                (result, failure) -> {
                    if (failure != null) {
                        deliver(sender, toolsMessage(selected, null, PersonalAccessState.UNKNOWN));
                        return;
                    }
                    AccessControl.UserSummary user = result.user();
                    deliver(
                            sender,
                            toolsMessage(
                                    selected,
                                    user,
                                    user == null
                                            ? PersonalAccessState.UNLINKED
                                            : PersonalAccessState.ACCOUNT));
                });
        return Command.SINGLE_SUCCESS;
    }

    private Component toolsMessage(
            ToolCatalog.Entry selected,
            AccessControl.UserSummary user,
            PersonalAccessState personalState) {
        return selected == null
                ? toolListMessage(user, personalState)
                : toolDetailMessage(selected, user, personalState);
    }

    private Component toolListMessage(
            AccessControl.UserSummary user, PersonalAccessState personalState) {
        TextComponent.Builder message = panel("Tool Field Guide");
        List<ToolCatalog.Entry> entries = this.toolCatalog.entries();
        long enabled = entries.stream().filter(this::isToolEnabled).count();
        long accessible =
                entries.stream()
                        .filter(entry -> isToolAccessible(entry, user, personalState))
                        .count();
        message.append(Component.newline());
        message.append(
                Component.text(
                        entries.size() + " supported  •  " + enabled + " enabled",
                        NamedTextColor.GRAY));
        if (personalState == PersonalAccessState.ACCOUNT && user != null) {
            message.append(
                    Component.text(
                            "  •  "
                                    + accessible
                                    + " available as "
                                    + profileTitle(user.accessProfile()),
                            NamedTextColor.GRAY));
        } else {
            message.append(Component.newline());
            message.append(
                    Component.text("  " + personalSummary(personalState), NamedTextColor.GRAY));
        }

        String category = null;
        for (ToolCatalog.Entry entry : entries) {
            if (!entry.category().equals(category)) {
                appendSection(message, categoryTitle(entry.category()));
                category = entry.category();
            }
            message.append(Component.newline());
            String command = "/dirt tools " + entry.name();
            message.append(
                    Component.text("  " + entry.name(), SECONDARY_ACCENT)
                            .clickEvent(ClickEvent.runCommand(command))
                            .hoverEvent(
                                    HoverEvent.showText(
                                            Component.text(
                                                    "Open " + entry.title(),
                                                    NamedTextColor.GRAY))));
            appendToolState(message, entry, user, personalState);
        }
        message.append(Component.newline()).append(Component.newline());
        message.append(
                Component.text(
                        "Select a tool to see inputs, results, and safety notes.",
                        NamedTextColor.DARK_GRAY));
        return message.build();
    }

    private Component toolDetailMessage(
            ToolCatalog.Entry entry,
            AccessControl.UserSummary user,
            PersonalAccessState personalState) {
        TextComponent.Builder message = panel(entry.title());
        message.append(Component.newline());
        message.append(Component.text(entry.description(), NamedTextColor.GRAY));
        appendValue(message, "Tool", entry.name());
        appendValue(message, "Category", categoryTitle(entry.category()));
        appendValue(message, "Required", profileTitle(entry.minimumProfile()));
        appendValue(message, "Server", isToolEnabled(entry) ? "enabled" : "disabled");
        appendValue(message, "Your access", toolState(entry, user, personalState));
        appendValue(message, "Changes world", entry.changesWorld() ? "yes" : "no");
        appendSection(message, "Use it when");
        message.append(Component.newline());
        message.append(Component.text("  " + entry.useWhen(), NamedTextColor.GRAY));
        appendSection(message, "Inputs");
        message.append(Component.newline());
        message.append(Component.text("  " + entry.inputs(), NamedTextColor.GRAY));
        appendSection(message, "Returns");
        message.append(Component.newline());
        message.append(Component.text("  " + entry.returns(), NamedTextColor.GRAY));
        appendSection(message, "Safety");
        message.append(Component.newline());
        message.append(Component.text("  " + entry.caution(), NamedTextColor.GRAY));
        message.append(Component.newline()).append(Component.newline());
        message.append(
                Component.text("‹ All tools", SECONDARY_ACCENT)
                        .clickEvent(ClickEvent.runCommand("/dirt tools"))
                        .hoverEvent(
                                HoverEvent.showText(
                                        Component.text(
                                                "Return to the tool list", NamedTextColor.GRAY))));
        return message.build();
    }

    private void appendToolState(
            TextComponent.Builder message,
            ToolCatalog.Entry entry,
            AccessControl.UserSummary user,
            PersonalAccessState personalState) {
        boolean available = isToolAccessible(entry, user, personalState);
        NamedTextColor color;
        if (available) {
            color = NamedTextColor.GREEN;
        } else if (!isToolEnabled(entry)) {
            color = NamedTextColor.DARK_GRAY;
        } else {
            color = NamedTextColor.YELLOW;
        }
        message.append(Component.text("  " + toolState(entry, user, personalState), color));
    }

    private String toolState(
            ToolCatalog.Entry entry,
            AccessControl.UserSummary user,
            PersonalAccessState personalState) {
        if (!isToolEnabled(entry)) {
            return "disabled";
        }
        return switch (personalState) {
            case NOT_APPLICABLE -> "enabled";
            case UNKNOWN -> "access unknown";
            case UNLINKED -> "link required";
            case ACCOUNT -> {
                if (user == null || user.status() != AccessControl.UserStatus.ACTIVE) {
                    yield "account disabled";
                }
                yield user.accessProfile().grants(entry.minimumProfile())
                        ? "available"
                        : "needs " + profileTitle(entry.minimumProfile());
            }
        };
    }

    private boolean isToolEnabled(ToolCatalog.Entry entry) {
        return this.config.bridge().allowedOperations().contains(entry.operation());
    }

    private boolean isToolAccessible(
            ToolCatalog.Entry entry,
            AccessControl.UserSummary user,
            PersonalAccessState personalState) {
        return personalState == PersonalAccessState.ACCOUNT
                && user != null
                && user.status() == AccessControl.UserStatus.ACTIVE
                && isToolEnabled(entry)
                && user.accessProfile().grants(entry.minimumProfile());
    }

    private static String personalSummary(PersonalAccessState state) {
        return switch (state) {
            case NOT_APPLICABLE -> "Personal access is available to linked in-game players.";
            case UNKNOWN -> "Personal access is unknown because the dashboard is unavailable.";
            case UNLINKED -> "No linked Dirt account; use /dirt link after signing in.";
            case ACCOUNT -> throw new IllegalArgumentException("Account summary requires a user");
        };
    }

    private static String categoryTitle(String category) {
        return switch (category) {
            case "status" -> "Status";
            case "inspection" -> "Inspection";
            case "editing" -> "Editing and undo";
            case "commands" -> "Commands";
            default -> throw new IllegalArgumentException("Unknown tool category");
        };
    }

    private static String profileTitle(AccessControl.AccessProfile profile) {
        String name = profile.wireName();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private int listInvitations(CommandSender sender, int page) {
        return runAsync(sender, () -> this.access.listInvitations(page), this::invitationsMessage);
    }

    private int createInvitation(
            CommandSender sender, String playerName, AccessControl.AccessProfile accessProfile) {
        Player target = this.server.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(
                    failureMessage("That player must be online to receive an invitation."));
            return 0;
        }
        sender.sendMessage(progressMessage());
        final CompletionStage<AccessControl.CreateInvitationResult> pending;
        try {
            pending =
                    this.access.createInvitation(
                            target.getUniqueId(), target.getName(), accessProfile);
        } catch (RuntimeException failure) {
            deliver(sender, accessFailureMessage(failure));
            return 0;
        }
        pending.whenComplete(
                (result, failure) -> {
                    if (failure != null) {
                        deliver(sender, accessFailureMessage(failure));
                        return;
                    }
                    deliverInvitation(sender, target.getUniqueId(), result);
                });
        return Command.SINGLE_SUCCESS;
    }

    private void deliverInvitation(
            CommandSender sender,
            java.util.UUID targetUuid,
            AccessControl.CreateInvitationResult result) {
        try {
            this.mainThread.run(
                    () -> {
                        Player target = this.server.getPlayer(targetUuid);
                        if (target == null || !target.isOnline()) {
                            revokeUndeliveredInvitation(sender, result.invitation().id());
                            return;
                        }
                        target.sendMessage(createdInvitationMessage(result));
                        if (!(sender instanceof Player player)
                                || !player.getUniqueId().equals(targetUuid)) {
                            sender.sendMessage(
                                    confirmation(
                                            "Invitation Sent",
                                            "Invitation sent privately to "
                                                    + target.getName()
                                                    + '.'));
                        }
                    });
        } catch (PaperMainThreadException ignored) {
            revokeUndeliveredInvitation(sender, result.invitation().id());
        }
    }

    private void revokeUndeliveredInvitation(CommandSender sender, String invitationId) {
        try {
            this.access
                    .revokeInvitation(invitationId)
                    .whenComplete(
                            (ignored, failure) ->
                                    deliver(
                                            sender,
                                            failureMessage(
                                                    failure == null
                                                            ? "The player left before the invitation could be sent. Try again when they return."
                                                            : "The player left before the invitation could be sent. The unused invitation will expire automatically.")));
        } catch (RuntimeException failure) {
            deliver(
                    sender,
                    failureMessage(
                            "The player left before the invitation could be sent. The unused invitation will expire automatically."));
        }
    }

    private int revokeInvitation(CommandSender sender, String id) {
        return runAsync(
                sender,
                () -> this.access.revokeInvitation(id),
                result ->
                        confirmation(
                                "Invitation Revoked",
                                "Invitation " + result.invitation().id() + " is revoked."));
    }

    private int createUserRecovery(CommandSender sender, String selector) {
        Player operator = requireInGameOperator(sender);
        if (operator == null) {
            return 0;
        }
        return runAsync(
                operator,
                () -> this.access.createUserRecovery(selector),
                this::userRecoveryMessage);
    }

    private int link(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    failureMessage("Minecraft account linking must be run by an in-game player."));
            return 0;
        }
        return runAsync(
                player,
                () ->
                        this.access.createMinecraftLinkChallenge(
                                player.getUniqueId(), player.getName()),
                this::minecraftLinkMessage);
    }

    private Player requireInGameOperator(CommandSender sender) {
        if (sender instanceof Player player && player.isOp()) {
            return player;
        }
        sender.sendMessage(
                failureMessage("This secret command must be run in-game by an operator."));
        return null;
    }

    private <T> int runAsync(
            CommandSender sender,
            Supplier<CompletionStage<T>> operation,
            Function<T, Component> successMessage) {
        sender.sendMessage(progressMessage());
        final CompletionStage<T> pending;
        try {
            pending = Objects.requireNonNull(operation.get(), "operation result");
        } catch (RuntimeException failure) {
            deliver(sender, accessFailureMessage(failure));
            return 0;
        }
        pending.whenComplete(
                (result, failure) -> {
                    Component message;
                    if (failure != null) {
                        message = accessFailureMessage(failure);
                    } else {
                        try {
                            message = Objects.requireNonNull(successMessage.apply(result));
                        } catch (RuntimeException renderFailure) {
                            message = accessFailureMessage(renderFailure);
                        }
                    }
                    deliver(sender, message);
                });
        return Command.SINGLE_SUCCESS;
    }

    private void deliver(CommandSender sender, Component message) {
        try {
            this.mainThread.run(() -> sender.sendMessage(message));
        } catch (PaperMainThreadException ignored) {
            // Plugin shutdown owns this boundary; never spill private command output to logs.
        }
    }

    private Component usersMessage(AccessControl.UserPage result) {
        TextComponent.Builder message = panel("Users");
        appendPageSummary(message, result.page(), result.totalPages(), result.totalItems());
        if (result.items().isEmpty()) {
            message.append(Component.newline());
            message.append(Component.text("  No users on this page.", NamedTextColor.GRAY));
        }
        for (AccessControl.UserSummary user : result.items()) {
            message.append(Component.newline()).append(Component.newline());
            message.append(Component.text("  " + user.username(), SECONDARY_ACCENT));
            message.append(
                    Component.text(
                            "  " + user.status().wireName(),
                            user.status() == AccessControl.UserStatus.ACTIVE
                                    ? NamedTextColor.GREEN
                                    : NamedTextColor.RED));
            message.append(Component.newline());
            message.append(
                    Component.text(
                            "    Access: " + profileTitle(user.accessProfile()),
                            NamedTextColor.GRAY));
            message.append(Component.newline());
            if (user.minecraftUuid() == null) {
                message.append(Component.text("    Minecraft: unlinked", NamedTextColor.GRAY));
            } else {
                message.append(
                        Component.text("    UUID: " + user.minecraftUuid(), NamedTextColor.GRAY));
            }
            message.append(Component.newline());
            message.append(
                    Component.text("    Account ID: " + user.id(), NamedTextColor.DARK_GRAY));
        }
        appendPageControls(message, "users", result.page(), result.totalPages());
        return message.build();
    }

    private Component invitationsMessage(AccessControl.InvitationPage result) {
        TextComponent.Builder message = panel("Invites");
        appendPageSummary(message, result.page(), result.totalPages(), result.totalItems());
        if (result.items().isEmpty()) {
            message.append(Component.newline());
            message.append(Component.text("  No invitations on this page.", NamedTextColor.GRAY));
        }
        for (AccessControl.InvitationSummary invitation : result.items()) {
            message.append(Component.newline()).append(Component.newline());
            Component id =
                    Component.text("  " + invitation.id(), SECONDARY_ACCENT)
                            .clickEvent(ClickEvent.copyToClipboard(invitation.id()))
                            .hoverEvent(copyHover("invitation ID"));
            message.append(id);
            message.append(
                    Component.text(
                            "  " + invitation.status().wireName(),
                            invitation.status() == AccessControl.InvitationStatus.PENDING
                                    ? NamedTextColor.GREEN
                                    : NamedTextColor.GRAY));
            message.append(Component.newline());
            message.append(
                    Component.text(
                            "    Player: "
                                    + invitation.minecraftAccount().name()
                                    + " ("
                                    + invitation.minecraftAccount().uuid()
                                    + ')',
                            NamedTextColor.GRAY));
            message.append(Component.newline());
            message.append(
                    Component.text(
                            "    Access: " + profileTitle(invitation.accessProfile()),
                            NamedTextColor.GRAY));
            message.append(Component.newline());
            message.append(
                    Component.text("    Expires: " + invitation.expiresAt(), NamedTextColor.GRAY));
        }
        appendPageControls(message, "invites", result.page(), result.totalPages());
        return message.build();
    }

    private Component createdInvitationMessage(AccessControl.CreateInvitationResult result) {
        return privateUrlMessage(
                "Your Dirt Invitation",
                "Linked to "
                        + result.invitation().minecraftAccount().name()
                        + " as "
                        + profileTitle(result.invitation().accessProfile())
                        + " • expires at "
                        + result.invitation().expiresAt(),
                "Open invitation",
                result.inviteUrl());
    }

    private Component userRecoveryMessage(AccessControl.UserRecoveryResult result) {
        return privateUrlMessage(
                "User Recovery",
                "Recovery for " + result.user().username() + " expires at " + result.expiresAt(),
                "Private recovery URL",
                result.recoveryUrl());
    }

    private Component minecraftLinkMessage(AccessControl.MinecraftLinkChallenge result) {
        TextComponent.Builder message = panel("Minecraft Link");
        message.append(Component.newline());
        message.append(
                Component.text(
                        "Private one-use challenge • expires at " + result.expiresAt(),
                        NamedTextColor.GRAY));
        message.append(Component.newline()).append(Component.newline());
        message.append(Component.text("  Code  ", NamedTextColor.GRAY));
        message.append(
                Component.text(result.code(), SECONDARY_ACCENT, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.copyToClipboard(result.code()))
                        .hoverEvent(copyHover("link code")));
        appendPrivateUrl(message, "Link URL", result.linkUrl());
        return message.build();
    }

    private static Component privateUrlMessage(String title, String detail, String label, URI url) {
        TextComponent.Builder message = panel(title);
        message.append(Component.newline());
        message.append(Component.text(detail, NamedTextColor.GRAY));
        appendPrivateUrl(message, label, url);
        message.append(Component.newline());
        message.append(
                Component.text(
                        "  Keep this URL private; it is shown only in this message.",
                        NamedTextColor.RED));
        return message.build();
    }

    private static void appendPrivateUrl(TextComponent.Builder message, String label, URI url) {
        String value = url.toString();
        message.append(Component.newline()).append(Component.newline());
        message.append(Component.text("  " + label + "  ", NamedTextColor.GRAY));
        message.append(
                Component.text(value, SECONDARY_ACCENT)
                        .clickEvent(ClickEvent.openUrl(value))
                        .hoverEvent(
                                HoverEvent.showText(
                                        Component.text("Click to open", NamedTextColor.GRAY))));
        message.append(Component.text("  ", NamedTextColor.DARK_GRAY));
        message.append(
                Component.text("[copy]", SECONDARY_ACCENT)
                        .clickEvent(ClickEvent.copyToClipboard(value))
                        .hoverEvent(copyHover("private URL")));
    }

    private static Component userMutationMessage(
            AccessControl.UserSummary user, String completedAction) {
        return confirmation(
                "User Updated", "User " + user.username() + " was " + completedAction + '.');
    }

    private static Component accessProfileMessage(AccessControl.UserSummary user) {
        return confirmation(
                "Access Updated",
                user.username()
                        + " has "
                        + profileTitle(user.accessProfile())
                        + " access. Profile changes require MCP clients to reconnect.");
    }

    private static Component confirmation(String title, String detail) {
        TextComponent.Builder message = panel(title);
        message.append(Component.newline());
        message.append(Component.text("● " + detail, NamedTextColor.GREEN));
        return message.build();
    }

    private static Component progressMessage() {
        TextComponent.Builder message = panel("Access");
        message.append(Component.newline());
        message.append(Component.text("Contacting the dashboard…", NamedTextColor.GRAY));
        return message.build();
    }

    private static Component accessFailureMessage(Throwable failure) {
        Throwable cause = unwrap(failure);
        String message =
                cause instanceof AccessControlException accessFailure
                        ? accessFailure.getMessage()
                        : "The dashboard access request failed.";
        return failureMessage(message);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static Component failureMessage(String detail) {
        TextComponent.Builder message = panel("Access Unavailable");
        message.append(Component.newline());
        message.append(Component.text("● " + detail, NamedTextColor.RED));
        return message.build();
    }

    private static void appendPageSummary(
            TextComponent.Builder message, int page, int totalPages, long totalItems) {
        message.append(Component.newline());
        int displayedTotalPages = Math.max(1, totalPages);
        message.append(
                Component.text(
                        "Page "
                                + page
                                + " / "
                                + displayedTotalPages
                                + "  •  "
                                + totalItems
                                + " total",
                        NamedTextColor.GRAY));
    }

    private static void appendPageControls(
            TextComponent.Builder message, String collection, int page, int totalPages) {
        int lastPage = Math.max(1, totalPages);
        if (page > lastPage) {
            message.append(Component.newline()).append(Component.newline());
            appendPageControl(message, collection, lastPage, "‹ Last page");
            return;
        }
        if (page <= 1 && page >= totalPages) {
            return;
        }
        message.append(Component.newline()).append(Component.newline());
        if (page > 1) {
            appendPageControl(message, collection, page - 1, "‹ Previous");
        }
        if (page > 1 && page < totalPages) {
            message.append(Component.text("  ", NamedTextColor.DARK_GRAY));
        }
        if (page < totalPages) {
            appendPageControl(message, collection, page + 1, "Next ›");
        }
    }

    private static void appendPageControl(
            TextComponent.Builder message, String collection, int page, String label) {
        String command = "/dirt " + collection + ' ' + page;
        message.append(
                Component.text(label, SECONDARY_ACCENT)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(
                                HoverEvent.showText(
                                        Component.text("Open page " + page, NamedTextColor.GRAY))));
    }

    private static HoverEvent<Component> copyHover(String value) {
        return HoverEvent.showText(Component.text("Click to copy " + value, NamedTextColor.GRAY));
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

    private static void appendValue(TextComponent.Builder message, String label, Object value) {
        message.append(Component.newline());
        message.append(Component.text("  " + label + "  ", NamedTextColor.GRAY));
        message.append(Component.text(String.valueOf(value), NamedTextColor.WHITE));
    }

    private static AccessControl.AccessProfile accessProfile(String value)
            throws CommandSyntaxException {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "viewer" -> AccessControl.AccessProfile.VIEWER;
            case "builder" -> AccessControl.AccessProfile.BUILDER;
            case "operator" -> AccessControl.AccessProfile.OPERATOR;
            default -> throw INVALID_ACCESS_PROFILE.create(value);
        };
    }

    private static boolean canBrowseTools(CommandSender sender) {
        return hasAdminPermission(sender)
                || (sender instanceof Player && hasLinkPermission(sender));
    }

    private static boolean hasAdminPermission(CommandSender sender) {
        return sender.isOp() && sender.hasPermission(ADMIN_PERMISSION);
    }

    private static boolean hasLinkPermission(CommandSender sender) {
        return sender.hasPermission(LINK_PERMISSION);
    }

    private enum PersonalAccessState {
        ACCOUNT,
        UNLINKED,
        UNKNOWN,
        NOT_APPLICABLE
    }
}
