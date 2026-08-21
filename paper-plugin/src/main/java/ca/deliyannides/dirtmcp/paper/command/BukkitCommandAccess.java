package ca.deliyannides.dirtmcp.paper.command;

import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.CommandResult;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Outcome;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Result;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Sender;
import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper adapter for console-authority command dispatch with scoped feedback capture. */
public final class BukkitCommandAccess implements PaperCommandService.CommandAccess {
    private static final String NOT_FOUND_MESSAGE = "Paper found no target for this command";
    private static final String DISPATCH_FAILURE_MESSAGE = "Paper command dispatch failed";
    private static final int MAXIMUM_CAUSE_DEPTH = 16;
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private final Server server;
    private final BooleanSupplier pluginEnabled;

    public BukkitCommandAccess(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.pluginEnabled = plugin::isEnabled;
    }

    BukkitCommandAccess(Server server, BooleanSupplier pluginEnabled) {
        this.server = Objects.requireNonNull(server, "server");
        this.pluginEnabled = Objects.requireNonNull(pluginEnabled, "pluginEnabled");
    }

    @Override
    public Result dispatch(List<String> commands, int maximumFeedbackCodePoints)
            throws OperationException {
        if (!this.pluginEnabled.getAsBoolean()) {
            throw unavailable("The Dirt MCP plugin is not enabled");
        }

        FeedbackBudget budget = new FeedbackBudget(maximumFeedbackCodePoints);
        List<FeedbackCapture> captures = new ArrayList<>(commands.size());
        List<PreparedCommand> preparedCommands = new ArrayList<>(commands.size());
        List<CommandResult> results = new ArrayList<>(commands.size());
        Sender senderDescription = null;
        try {
            for (String command : commands) {
                FeedbackCapture feedback = new FeedbackCapture(budget);
                captures.add(feedback);
                CommandSender sender = this.server.createCommandSender(feedback::accept);
                Sender currentDescription = describe(sender);
                if (senderDescription == null) {
                    senderDescription = currentDescription;
                } else if (!senderDescription.equals(currentDescription)) {
                    throw unavailable("Paper provided inconsistent command senders");
                }
                preparedCommands.add(new PreparedCommand(command, sender, feedback));
            }

            for (PreparedCommand prepared : preparedCommands) {
                Outcome outcome;
                String message = null;
                String rawMessage = null;
                try {
                    outcome =
                            this.server.dispatchCommand(prepared.sender(), prepared.command())
                                    ? Outcome.DISPATCHED
                                    : Outcome.NOT_FOUND;
                    if (outcome == Outcome.NOT_FOUND) {
                        message = NOT_FOUND_MESSAGE;
                    }
                } catch (CommandException exception) {
                    outcome = Outcome.DISPATCH_FAILED;
                    rawMessage = messageOrDefault(exception, DISPATCH_FAILURE_MESSAGE);
                    message = deepestCauseMessage(exception, rawMessage);
                } finally {
                    // Feedback cannot change a result after its synchronous dispatch ends.
                    prepared.feedback().close();
                }
                results.add(
                        new CommandResult(
                                prepared.command(),
                                outcome,
                                prepared.feedback().snapshot(),
                                message,
                                rawMessage));
                if (outcome != Outcome.DISPATCHED) {
                    break;
                }
            }
        } finally {
            // This also closes senders prepared for commands skipped by fail-fast dispatch.
            captures.forEach(FeedbackCapture::close);
        }
        return new Result(senderDescription, budget.truncated(), results);
    }

    private static Sender describe(CommandSender sender) throws OperationException {
        if (sender == null || !sender.isOp() || sender instanceof Player) {
            throw unavailable("Paper did not provide an operator-level non-player command sender");
        }
        String name = sender.getName();
        if (name == null || name.isBlank()) {
            throw unavailable("Paper did not provide a named command sender");
        }
        return new Sender(name, true, false);
    }

    private static String deepestCauseMessage(CommandException exception, String fallback) {
        String message = null;
        Throwable cause = exception.getCause();
        for (int depth = 0;
                cause != null && cause != exception && depth < MAXIMUM_CAUSE_DEPTH;
                depth++) {
            String candidate = cause.getMessage();
            if (candidate != null && !candidate.isBlank()) {
                message = candidate;
            }
            cause = cause.getCause();
        }
        return message == null ? fallback : message;
    }

    private static String messageOrDefault(Throwable failure, String fallback) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static OperationException unavailable(String message) {
        return new OperationException(
                OperationFailure.SERVER_UNAVAILABLE,
                message,
                new ErrorDetails.ServerUnavailable.PaperUnavailable());
    }

    private record PreparedCommand(
            String command, CommandSender sender, FeedbackCapture feedback) {}

    private static final class FeedbackBudget {
        private int remaining;
        private boolean truncated;

        private FeedbackBudget(int maximumCodePoints) {
            if (maximumCodePoints < 1) {
                throw new IllegalArgumentException("maximumCodePoints must be positive");
            }
            this.remaining = maximumCodePoints;
        }

        private synchronized String retain(Component component) {
            String text = PLAIN.serialize(Objects.requireNonNull(component, "component"));
            if (text.isEmpty()) {
                return null;
            }
            int codePoints = text.codePointCount(0, text.length());
            if (codePoints <= this.remaining) {
                this.remaining -= codePoints;
                return text;
            }
            this.truncated = true;
            if (this.remaining == 0) {
                return null;
            }
            int end = text.offsetByCodePoints(0, this.remaining);
            this.remaining = 0;
            return text.substring(0, end);
        }

        private synchronized boolean truncated() {
            return this.truncated;
        }
    }

    private static final class FeedbackCapture {
        private final FeedbackBudget budget;
        private final List<String> messages = new ArrayList<>();
        private boolean open = true;

        private FeedbackCapture(FeedbackBudget budget) {
            this.budget = budget;
        }

        private synchronized void accept(Component component) {
            if (!this.open) {
                return;
            }
            String retained = this.budget.retain(component);
            if (retained != null) {
                this.messages.add(retained);
            }
        }

        private synchronized void close() {
            this.open = false;
        }

        private synchronized List<String> snapshot() {
            if (this.open) {
                throw new IllegalStateException("Feedback capture must be closed before snapshot");
            }
            return List.copyOf(this.messages);
        }
    }
}
