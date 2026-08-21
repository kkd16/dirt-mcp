package ca.deliyannides.dirtmcp.paper.command;

import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Dispatches registered Minecraft commands in order until the first failed dispatch. */
@FunctionalInterface
public interface RunMinecraftCommands {
    Result runCommands(Request request) throws OperationException;

    record Request(List<String> commands) {
        public Request {
            // Preserve malformed null entries for operation-layer INVALID_REQUEST reporting.
            commands =
                    commands == null
                            ? null
                            : Collections.unmodifiableList(new ArrayList<>(commands));
        }
    }

    record Result(Sender sender, boolean feedbackTruncated, List<CommandResult> results) {
        public Result {
            Objects.requireNonNull(sender, "sender");
            results = List.copyOf(Objects.requireNonNull(results, "results"));
            if (results.isEmpty()) {
                throw new IllegalArgumentException("results must not be empty");
            }
            for (int index = 0; index < results.size() - 1; index++) {
                if (results.get(index).outcome() != Outcome.DISPATCHED) {
                    throw new IllegalArgumentException(
                            "Only the final command result may report a command failure");
                }
            }
        }
    }

    record Sender(String name, boolean isOperator, boolean isPlayer) {
        public Sender {
            name = requireText(name, "name");
            if (!isOperator || isPlayer) {
                throw new IllegalArgumentException(
                        "The command sender must be an operator-level non-player");
            }
        }
    }

    record CommandResult(
            String command,
            Outcome outcome,
            List<String> feedback,
            String message,
            String rawMessage) {
        public CommandResult {
            command = requireNormalizedCommand(command);
            Objects.requireNonNull(outcome, "outcome");
            feedback = List.copyOf(Objects.requireNonNull(feedback, "feedback"));
            for (String entry : feedback) {
                if (entry.isEmpty()) {
                    throw new IllegalArgumentException("feedback[] must not be empty");
                }
            }
            switch (outcome) {
                case DISPATCHED -> {
                    requireAbsent(message, "message");
                    requireAbsent(rawMessage, "rawMessage");
                }
                case NOT_FOUND -> {
                    message = requireText(message, "message");
                    requireAbsent(rawMessage, "rawMessage");
                }
                case DISPATCH_FAILED -> {
                    message = requireText(message, "message");
                    rawMessage = requireText(rawMessage, "rawMessage");
                }
            }
        }
    }

    enum Outcome {
        DISPATCHED("dispatched"),
        NOT_FOUND("not_found"),
        DISPATCH_FAILED("dispatch_failed");

        private final String wireName;

        Outcome(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return this.wireName;
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireNormalizedCommand(String command) {
        String checked = requireText(command, "command");
        if (!checked.equals(checked.strip())
                || checked.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "command must be normalized and contain no ISO control characters");
        }
        return checked;
    }

    private static void requireAbsent(String value, String name) {
        if (value != null) {
            throw new IllegalArgumentException(name + " must be null for this outcome");
        }
    }
}
