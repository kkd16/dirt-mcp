package ca.deliyannides.dirtmcp.paper.status;

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
        HealthTarget target = callOnMain(this.paperAccess::prepareHealthCheck, true);
        try {
            target.verify();
            return new PingServer.Result("ok");
        } catch (Exception exception) {
            throw new OperationException(
                    OperationFailure.UNHEALTHY,
                    "Dirt MCP could not open a Paper-backed FAWE session",
                    exception);
        }
    }

    @Override
    public GetServerStatus.Result getStatus() throws OperationException {
        return callOnMain(this.paperAccess::captureStatus, false);
    }

    private <T> T callOnMain(MainThread.CheckedSupplier<T> action, boolean health)
            throws OperationException {
        try {
            return this.mainThread.call(action);
        } catch (PaperMainThreadException exception) {
            if (exception.getCause() instanceof OperationException operationException) {
                throw operationException;
            }
            throw new OperationException(
                    health ? OperationFailure.UNHEALTHY : OperationFailure.SERVER_UNAVAILABLE,
                    health
                            ? "The end-to-end health check could not access Paper"
                            : "Could not read Paper server context",
                    exception);
        }
    }

    public interface PaperStatusAccess {
        HealthTarget prepareHealthCheck() throws OperationException;

        GetServerStatus.Result captureStatus() throws OperationException;
    }

    @FunctionalInterface
    public interface HealthTarget {
        void verify() throws Exception;
    }
}
