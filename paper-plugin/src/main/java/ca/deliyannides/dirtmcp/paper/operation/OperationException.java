package ca.deliyannides.dirtmcp.paper.operation;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import java.io.Serial;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class OperationException extends Exception {
    @Serial private static final long serialVersionUID = 1L;

    private final OperationFailure failure;
    private final ErrorDetails details;
    private final UUID editId;

    public OperationException(OperationFailure failure, String message, ErrorDetails details) {
        this(failure, message, details, null, null);
    }

    public OperationException(
            OperationFailure failure, String message, ErrorDetails details, Throwable cause) {
        this(failure, message, details, cause, null);
    }

    public OperationException(
            OperationFailure failure,
            String message,
            ErrorDetails details,
            Throwable cause,
            UUID editId) {
        super(requireMessage(message), cause);
        this.failure = Objects.requireNonNull(failure, "failure");
        requireMatchingDetails(failure, details);
        this.details = details;
        if (editId != null) {
            UuidV4.require(editId, "editId");
        }
        this.editId = editId;
    }

    public OperationFailure failure() {
        return this.failure;
    }

    public Optional<ErrorDetails> details() {
        return Optional.ofNullable(this.details);
    }

    public Optional<UUID> editId() {
        return Optional.ofNullable(this.editId);
    }

    private static void requireMatchingDetails(OperationFailure failure, ErrorDetails details) {
        boolean matches =
                switch (failure) {
                    case INTERNAL_ERROR -> details == null;
                    case INVALID_REQUEST -> details instanceof ErrorDetails.InvalidRequest;
                    case CHANGE_LIMIT_EXCEEDED ->
                            details instanceof ErrorDetails.ChangeLimitExceeded;
                    case EDIT_NOT_FOUND -> details instanceof ErrorDetails.EditNotFound;
                    case EDIT_NOT_LATEST -> details instanceof ErrorDetails.EditNotLatest;
                    case HISTORY_CAPACITY_EXCEEDED ->
                            details instanceof ErrorDetails.HistoryCapacityExceeded;
                    case PLAYER_NOT_FOUND -> details instanceof ErrorDetails.PlayerNotFound;
                    case PLAYER_UNAVAILABLE -> details instanceof ErrorDetails.PlayerUnavailable;
                    case REGION_TOO_LARGE -> details instanceof ErrorDetails.RegionTooLarge;
                    case RESULT_TOO_LARGE -> details instanceof ErrorDetails.ResultTooLarge;
                    case SERVER_UNAVAILABLE -> details instanceof ErrorDetails.ServerUnavailable;
                    case UNHEALTHY -> details instanceof ErrorDetails.Unhealthy;
                    case WORLD_BUSY -> details instanceof ErrorDetails.WorldBusy;
                    case WORLD_NOT_FOUND -> details instanceof ErrorDetails.WorldNotFound;
                    case WORLD_UNAVAILABLE -> details instanceof ErrorDetails.WorldUnavailable;
                };
        if (!matches) {
            throw new IllegalArgumentException("Error details do not match " + failure);
        }
    }

    private static String requireMessage(String message) {
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("Error message must not be empty");
        }
        return message;
    }
}
