package ca.deliyannides.dirtmcp.paper.command;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Validates command batches and crosses once onto Paper's primary thread. */
public final class PaperCommandService implements RunMinecraftCommands {
    private final MainThread mainThread;
    private final CommandAccess paperAccess;
    private final int maximumCommands;
    private final int maximumFeedbackCodePoints;

    public PaperCommandService(
            MainThread mainThread,
            CommandAccess paperAccess,
            int maximumCommands,
            int maximumFeedbackCodePoints) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.paperAccess = Objects.requireNonNull(paperAccess, "paperAccess");
        if (maximumCommands < 1 || maximumFeedbackCodePoints < 1) {
            throw new IllegalArgumentException("Command limits must be positive");
        }
        this.maximumCommands = maximumCommands;
        this.maximumFeedbackCodePoints = maximumFeedbackCodePoints;
    }

    @Override
    public Result runCommands(Request request) throws OperationException {
        List<String> commands = normalize(request, this.maximumCommands);
        try {
            return this.mainThread.call(
                    () -> this.paperAccess.dispatch(commands, this.maximumFeedbackCodePoints));
        } catch (PaperMainThreadException exception) {
            if (exception.getCause() instanceof OperationException operationException) {
                throw operationException;
            }
            throw new OperationException(
                    OperationFailure.SERVER_UNAVAILABLE,
                    "Paper could not execute the commands",
                    new ErrorDetails.ServerUnavailable.PaperUnavailable(),
                    exception);
        }
    }

    private static List<String> normalize(Request request, int maximumCommands)
            throws OperationException {
        Objects.requireNonNull(request, "request");
        List<String> requested = request.commands();
        if (requested.isEmpty()) {
            throw invalid(
                    "commands must contain at least one command",
                    new ErrorDetails.InvalidRequest.InvalidValue("commands"));
        }
        if (requested.size() > maximumCommands) {
            throw invalid(
                    "commands may contain at most " + maximumCommands + " entries",
                    new ErrorDetails.InvalidRequest.TooManyItems(
                            List.of("commands"), maximumCommands));
        }

        List<String> normalized = new ArrayList<>(requested.size());
        for (int index = 0; index < requested.size(); index++) {
            String field = "commands[" + index + "]";
            String command = requested.get(index);
            if (command.codePoints().anyMatch(Character::isISOControl)) {
                throw invalidCommand(field);
            }
            command = command.strip();
            if (command.startsWith("/")) {
                command = command.substring(1).strip();
            }
            if (command.isEmpty()) {
                throw invalidCommand(field);
            }
            normalized.add(command);
        }
        return List.copyOf(normalized);
    }

    private static OperationException invalid(String message, ErrorDetails.InvalidRequest details) {
        return new OperationException(OperationFailure.INVALID_REQUEST, message, details);
    }

    private static OperationException invalidCommand(String field) {
        return invalid(
                field
                        + " must be non-empty after normalization and contain no ISO control characters",
                new ErrorDetails.InvalidRequest.InvalidValue(field));
    }

    @FunctionalInterface
    public interface CommandAccess {
        Result dispatch(List<String> commands, int maximumFeedbackCodePoints)
                throws OperationException;
    }
}
