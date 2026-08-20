package ca.deliyannides.dirtmcp.paper.command;

import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.CommandResult;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Outcome;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Result;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Sender;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandException;

public final class PaperCommandService implements RunMinecraftCommands {
    private static final String NOT_FOUND_MESSAGE = "Paper found no target for this command";

    private final MainThread mainThread;
    private final PaperCommandAccess paperAccess;
    private final int maxCommands;
    private final int maxFeedbackCharacters;

    public PaperCommandService(
            MainThread mainThread,
            PaperCommandAccess paperAccess,
            int maxCommands,
            int maxFeedbackCharacters) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.paperAccess = Objects.requireNonNull(paperAccess, "paperAccess");
        if (maxCommands < 1 || maxFeedbackCharacters < 1) {
            throw new IllegalArgumentException("Command limits must be positive");
        }
        this.maxCommands = maxCommands;
        this.maxFeedbackCharacters = maxFeedbackCharacters;
    }

    @Override
    public Result runCommands(Request request) throws OperationException {
        List<String> commands = normalize(request, this.maxCommands);
        try {
            return this.mainThread.call(
                    () -> this.paperAccess.run(commands, this.maxFeedbackCharacters));
        } catch (PaperMainThreadException exception) {
            if (exception.getCause() instanceof OperationException operationException) {
                throw operationException;
            }
            throw new OperationException(
                    OperationFailure.SERVER_UNAVAILABLE,
                    "Paper could not execute the commands",
                    exception);
        }
    }

    static List<String> normalize(Request request, int maximumCommands) throws OperationException {
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

    static Result runBatch(
            List<String> commands,
            int maxFeedbackCharacters,
            Sender sender,
            CommandDispatch dispatch) {
        FeedbackBudget budget = new FeedbackBudget(maxFeedbackCharacters);
        List<CommandResult> results = new ArrayList<>(commands.size());
        for (String command : commands) {
            List<String> feedback = Collections.synchronizedList(new ArrayList<>());
            try {
                if (dispatch.dispatch(command, component -> budget.capture(component, feedback))) {
                    results.add(
                            new CommandResult(
                                    command, Outcome.DISPATCHED, snapshot(feedback), null, null));
                } else {
                    results.add(
                            new CommandResult(
                                    command,
                                    Outcome.NOT_FOUND,
                                    snapshot(feedback),
                                    NOT_FOUND_MESSAGE,
                                    null));
                }
            } catch (CommandException exception) {
                String rawMessage = messageOrDefault(exception, "Paper command dispatch failed");
                results.add(
                        new CommandResult(
                                command,
                                Outcome.DISPATCH_FAILED,
                                snapshot(feedback),
                                actionableMessage(exception, rawMessage),
                                rawMessage));
            }
        }
        return new Result(sender, budget.truncated(), results);
    }

    private static OperationException invalid(String message) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message);
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
            if (text.isEmpty()) {
                return;
            }
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

    @FunctionalInterface
    public interface PaperCommandAccess {
        Result run(List<String> commands, int maxFeedbackCharacters) throws OperationException;
    }
}
