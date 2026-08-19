package ca.deliyannides.dirtmcp.paper.command;

import ca.deliyannides.dirtmcp.paper.command.CommandRunner.CommandOutcome;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.CommandResult;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.CommandRunnerException;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.Failure;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.RunCommandsRequest;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.RunCommandsResult;
import ca.deliyannides.dirtmcp.paper.command.CommandRunner.Sender;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperCommandRunner implements CommandRunner {
    private static final String NOT_FOUND_MESSAGE = "Paper found no target for this command";

    private final JavaPlugin plugin;
    private final int maxCommandsPerRequest;
    private final int maxFeedbackCharacters;

    public PaperCommandRunner(
            JavaPlugin plugin, int maxCommandsPerRequest, int maxFeedbackCharacters) {
        if (maxCommandsPerRequest < 1 || maxFeedbackCharacters < 1) {
            throw new IllegalArgumentException("Command limits must be positive");
        }
        this.plugin = plugin;
        this.maxCommandsPerRequest = maxCommandsPerRequest;
        this.maxFeedbackCharacters = maxFeedbackCharacters;
    }

    @Override
    public RunCommandsResult runCommands(RunCommandsRequest request) throws CommandRunnerException {
        List<String> commands = normalize(request, this.maxCommandsPerRequest);
        Future<RunCommandsResult> result =
                this.plugin
                        .getServer()
                        .getScheduler()
                        .callSyncMethod(this.plugin, () -> runOnMainThread(commands));
        try {
            return result.get();
        } catch (InterruptedException exception) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw new CommandRunnerException(
                    Failure.SERVER_UNAVAILABLE, "Command execution was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof CommandRunnerException runnerException) {
                throw runnerException;
            }
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new CommandRunnerException(
                    Failure.SERVER_UNAVAILABLE,
                    "Paper could not execute the commands",
                    exception.getCause());
        }
    }

    static List<String> normalize(RunCommandsRequest request, int maximumCommands)
            throws CommandRunnerException {
        if (request == null || request.commands() == null || request.commands().isEmpty()) {
            throw invalid("commands must contain at least one command");
        }
        if (request.commands().size() > maximumCommands) {
            throw invalid("commands may contain at most " + maximumCommands + " entries");
        }

        List<String> normalized = new ArrayList<>(request.commands().size());
        for (int index = 0; index < request.commands().size(); index++) {
            String command = request.commands().get(index);
            if (command == null || command.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid("commands[" + index + "] must be a non-empty single-line string");
            }
            command = command.strip();
            if (command.startsWith("/")) {
                command = command.substring(1).strip();
            }
            if (command.isEmpty()) {
                throw invalid("commands[" + index + "] must be a non-empty single-line string");
            }
            normalized.add(command);
        }
        return List.copyOf(normalized);
    }

    private RunCommandsResult runOnMainThread(List<String> commands) throws CommandRunnerException {
        if (!this.plugin.isEnabled()) {
            throw new CommandRunnerException(
                    Failure.SERVER_UNAVAILABLE, "The Dirt MCP plugin is not enabled");
        }

        Server server = this.plugin.getServer();
        CommandSender descriptionSender = server.createCommandSender(component -> {});
        if (!descriptionSender.isOp() || descriptionSender instanceof Player) {
            throw new CommandRunnerException(
                    Failure.SERVER_UNAVAILABLE,
                    "Paper did not provide an operator-level non-player command sender");
        }
        Sender senderDescription =
                new Sender(
                        descriptionSender.getName(),
                        descriptionSender.isOp(),
                        descriptionSender instanceof Player);
        return runBatch(
                commands,
                this.maxFeedbackCharacters,
                senderDescription,
                (command, feedback) -> {
                    CommandSender sender = server.createCommandSender(feedback);
                    return server.dispatchCommand(sender, command);
                });
    }

    static RunCommandsResult runBatch(
            List<String> commands,
            int maxFeedbackCharacters,
            Sender senderDescription,
            CommandDispatch dispatch) {
        FeedbackBudget budget = new FeedbackBudget(maxFeedbackCharacters);
        List<CommandResult> results = new ArrayList<>(commands.size());

        for (String command : commands) {
            List<String> feedback = Collections.synchronizedList(new ArrayList<>());
            try {
                if (dispatch.dispatch(command, component -> budget.capture(component, feedback))) {
                    results.add(
                            new CommandResult(
                                    command,
                                    CommandOutcome.DISPATCHED,
                                    snapshot(feedback),
                                    null,
                                    null));
                } else {
                    results.add(
                            new CommandResult(
                                    command,
                                    CommandOutcome.NOT_FOUND,
                                    snapshot(feedback),
                                    NOT_FOUND_MESSAGE,
                                    null));
                }
            } catch (CommandException exception) {
                String rawMessage = messageOrDefault(exception, "Paper command dispatch failed");
                results.add(
                        new CommandResult(
                                command,
                                CommandOutcome.DISPATCH_FAILED,
                                snapshot(feedback),
                                actionableMessage(exception, rawMessage),
                                rawMessage));
            }
        }

        return new RunCommandsResult(senderDescription, budget.truncated(), List.copyOf(results));
    }

    private static CommandRunnerException invalid(String message) {
        return new CommandRunnerException(Failure.INVALID_REQUEST, message);
    }

    private static List<String> snapshot(List<String> feedback) {
        synchronized (feedback) {
            return List.copyOf(feedback);
        }
    }

    private static String actionableMessage(CommandException exception, String fallback) {
        String message = null;
        Throwable cause = exception.getCause();
        for (int depth = 0; cause != null && cause != exception && depth < 16; depth++) {
            String candidate = cause.getMessage();
            if (candidate != null && !candidate.isBlank()) {
                message = candidate;
            }
            Throwable next = cause.getCause();
            if (next == cause) {
                break;
            }
            cause = next;
        }
        return message == null ? fallback : message;
    }

    private static String messageOrDefault(Throwable throwable, String fallback) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static final class FeedbackBudget {
        private int remaining;
        private boolean truncated;

        private FeedbackBudget(int maximumCharacters) {
            this.remaining = maximumCharacters;
        }

        private synchronized void capture(Component component, List<String> destination) {
            String text = PlainTextComponentSerializer.plainText().serialize(component);
            int characters = text.codePointCount(0, text.length());
            if (characters <= this.remaining) {
                destination.add(text);
                this.remaining -= characters;
                return;
            }
            if (this.remaining > 0) {
                int end = text.offsetByCodePoints(0, this.remaining);
                destination.add(text.substring(0, end));
                this.remaining = 0;
            }
            this.truncated = true;
        }

        private synchronized boolean truncated() {
            return this.truncated;
        }
    }

    @FunctionalInterface
    interface CommandDispatch {
        boolean dispatch(String command, Consumer<? super Component> feedback)
                throws CommandException;
    }
}
