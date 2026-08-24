package ca.deliyannides.dirtmcp.paper.status;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.Objects;

public final class PaperServerStatusService implements PingServer, GetServerStatus {
    private final MainThread mainThread;
    private final PaperStatusAccess paperAccess;

    public PaperServerStatusService(MainThread mainThread, PaperStatusAccess paperAccess) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.paperAccess = Objects.requireNonNull(paperAccess, "paperAccess");
    }

    @Override
    public PingServer.Result ping() throws OperationException {
        Runnable target = callOnMain(this.paperAccess::prepareHealthCheck, true);
        try {
            target.run();
            return new PingServer.Result("ok");
        } catch (RuntimeException exception) {
            throw new OperationException(
                    OperationFailure.UNHEALTHY,
                    "Dirt MCP could not open a Paper-backed FAWE session",
                    new ErrorDetails.Unhealthy.HealthCheckFailed(),
                    exception);
        }
    }

    @Override
    public GetServerStatus.Result getStatus(GetServerStatus.Request request)
            throws OperationException {
        Objects.requireNonNull(request, "request");
        return callOnMain(() -> this.paperAccess.captureStatus(request), false);
    }

    private <T> T callOnMain(MainThread.CheckedSupplier<T> action, boolean health)
            throws OperationException {
        try {
            return this.mainThread.call(action);
        } catch (PaperMainThreadException exception) {
            if (exception.getCause() instanceof OperationException operationException) {
                throw operationException;
            }
            if (health) {
                throw new OperationException(
                        OperationFailure.UNHEALTHY,
                        "The end-to-end health check could not access Paper",
                        new ErrorDetails.Unhealthy.PaperUnavailable(),
                        exception);
            }
            throw new OperationException(
                    OperationFailure.SERVER_UNAVAILABLE,
                    "Could not read Paper server context",
                    new ErrorDetails.ServerUnavailable.PaperUnavailable(),
                    exception);
        }
    }

    public interface PaperStatusAccess {
        Runnable prepareHealthCheck() throws OperationException;

        GetServerStatus.Result captureStatus(GetServerStatus.Request request)
                throws OperationException;
    }
}
