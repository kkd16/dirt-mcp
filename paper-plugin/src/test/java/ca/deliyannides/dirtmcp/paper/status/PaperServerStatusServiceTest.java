package ca.deliyannides.dirtmcp.paper.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PaperServerStatusServiceTest {
    private static final GetServerStatus.Request REQUEST =
            new GetServerStatus.Request(true, false, true);

    @Test
    void capturesStatusThroughTheMainThreadBoundaryAndForwardsSelection() throws Exception {
        GetServerStatus.Result expected = status();
        var mainThread = new DirectMainThread();
        var access = new FixedAccess(expected);
        var service = new PaperServerStatusService(mainThread, access);

        assertEquals(expected, service.getStatus(REQUEST));
        assertEquals(REQUEST, access.request);
        assertEquals(1, mainThread.calls);
    }

    @Test
    void preservesExpectedStatusFailures() {
        OperationException expected =
                new OperationException(
                        OperationFailure.SERVER_UNAVAILABLE,
                        "FAWE unavailable",
                        new ErrorDetails.ServerUnavailable.DependencyUnavailable());
        var service =
                new PaperServerStatusService(
                        new DirectMainThread(),
                        new PaperServerStatusService.PaperStatusAccess() {
                            @Override
                            public Runnable prepareHealthCheck() {
                                return () -> {};
                            }

                            @Override
                            public GetServerStatus.Result captureStatus(
                                    GetServerStatus.Request ignoredRequest)
                                    throws OperationException {
                                throw expected;
                            }
                        });

        assertEquals(
                expected, assertThrows(OperationException.class, () -> service.getStatus(REQUEST)));
    }

    @Test
    void verifiesHealthOutsideTheMainThreadBoundary() throws Exception {
        var mainThread = new DirectMainThread();
        boolean[] verified = {false};
        var service =
                new PaperServerStatusService(
                        mainThread,
                        new FixedAccess(status()) {
                            @Override
                            public Runnable prepareHealthCheck() {
                                return () -> verified[0] = true;
                            }
                        });

        assertEquals(new PingServer.Result("ok"), service.ping());
        assertEquals(1, mainThread.calls);
        assertEquals(true, verified[0]);
    }

    @Test
    void mapsMainThreadFailureByOperation() {
        var service =
                new PaperServerStatusService(new FailingMainThread(), new FixedAccess(status()));

        assertEquals(
                OperationFailure.SERVER_UNAVAILABLE,
                assertThrows(OperationException.class, () -> service.getStatus(REQUEST)).failure());
        assertEquals(
                OperationFailure.UNHEALTHY,
                assertThrows(OperationException.class, service::ping).failure());
    }

    @Test
    void permitsExplicitlyExcludedStatusSections() {
        GetServerStatus.Result result =
                new GetServerStatus.Result(
                        new GetServerStatus.Builds("26.2", "Paper", "Dirt", "FAWE"),
                        new GetServerStatus.Performance(20, 1),
                        null,
                        null,
                        null);

        assertEquals(null, result.players());
        assertEquals(null, result.worlds());
        assertEquals(null, result.configuration());
    }

    private static GetServerStatus.Result status() {
        return new GetServerStatus.Result(
                new GetServerStatus.Builds("26.2", "Paper", "Dirt", "FAWE"),
                new GetServerStatus.Performance(20, 1),
                new GetServerStatus.PlayerSummary(0, 20, List.of()),
                null,
                new GetServerStatus.EffectiveConfiguration(
                        new GetServerStatus.EffectiveLimits(
                                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13),
                        new GetServerStatus.EffectiveEditHistory(2, 3, 4)));
    }

    private static final class DirectMainThread implements MainThread {
        private int calls;

        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            this.calls++;
            try {
                return action.get();
            } catch (Exception exception) {
                throw new PaperMainThreadException("failed", exception);
            }
        }

        @Override
        public void close() {}
    }

    private static final class FailingMainThread implements MainThread {
        @Override
        public <T> T call(CheckedSupplier<T> action) throws PaperMainThreadException {
            throw new PaperMainThreadException("stopping");
        }

        @Override
        public void close() {}
    }

    private static class FixedAccess implements PaperServerStatusService.PaperStatusAccess {
        private final GetServerStatus.Result status;
        private GetServerStatus.Request request;

        private FixedAccess(GetServerStatus.Result status) {
            this.status = status;
        }

        @Override
        public Runnable prepareHealthCheck() {
            return () -> {};
        }

        @Override
        public GetServerStatus.Result captureStatus(GetServerStatus.Request request) {
            this.request = request;
            return this.status;
        }
    }
}
