package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.util.concurrent.Semaphore;

/** Shared admission limit for bounded world and player inspections. */
public final class InspectionAdmission {
    private final Semaphore permits;
    private final int maximumConcurrentInspections;

    public InspectionAdmission(int maximumConcurrentInspections) {
        if (maximumConcurrentInspections < 1) {
            throw new IllegalArgumentException("maximumConcurrentInspections must be positive");
        }
        this.permits = new Semaphore(maximumConcurrentInspections);
        this.maximumConcurrentInspections = maximumConcurrentInspections;
    }

    <T> T execute(Inspection<T> inspection) throws OperationException {
        if (!this.permits.tryAcquire()) {
            throw new OperationException(
                    OperationFailure.SERVER_UNAVAILABLE,
                    "The server is handling too many inspections",
                    new ErrorDetails.ServerUnavailable.InspectionBusy(
                            this.maximumConcurrentInspections));
        }
        try {
            return inspection.run();
        } finally {
            this.permits.release();
        }
    }

    @FunctionalInterface
    interface Inspection<T> {
        T run() throws OperationException;
    }
}
