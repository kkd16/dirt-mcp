package ca.deliyannides.dirtmcp.paper.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.config.McpTool;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import ca.deliyannides.dirtmcp.paper.platform.PaperMainThreadException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PaperServerStatusServiceTest {
    @Test
    void capturesStatusThroughTheMainThreadBoundary() throws Exception {
        GetServerStatus.Result expected = status();
        var mainThread = new DirectMainThread();
        var service = new PaperServerStatusService(mainThread, new FixedAccess(expected));

        assertEquals(expected, service.getStatus());
        assertEquals(1, mainThread.calls);
    }

    @Test
    void preservesExpectedStatusFailures() {
        OperationException expected =
                new OperationException(OperationFailure.SERVER_UNAVAILABLE, "FAWE unavailable");
        var service =
                new PaperServerStatusService(
                        new DirectMainThread(),
                        new PaperServerStatusService.PaperStatusAccess() {
                            @Override
                            public PaperServerStatusService.HealthTarget prepareHealthCheck() {
                                return () -> {};
                            }

                            @Override
                            public GetServerStatus.Result captureStatus()
                                    throws OperationException {
                                throw expected;
                            }
                        });

        assertEquals(expected, assertThrows(OperationException.class, service::getStatus));
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
                            public PaperServerStatusService.HealthTarget prepareHealthCheck() {
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
                assertThrows(OperationException.class, service::getStatus).failure());
        assertEquals(
                OperationFailure.UNHEALTHY,
                assertThrows(OperationException.class, service::ping).failure());
    }

    @Test
    void rejectsIncompleteUnknownAndNullToolFlags() {
        Map<String, Boolean> incomplete = new HashMap<>(allTools());
        incomplete.remove(McpTool.UNDO_EDIT.id());
        assertThrows(IllegalArgumentException.class, () -> status(incomplete));

        Map<String, Boolean> unknown = new HashMap<>(allTools());
        unknown.put("not_a_tool", true);
        assertThrows(IllegalArgumentException.class, () -> status(unknown));

        Map<String, Boolean> nullValue = new HashMap<>(allTools());
        nullValue.put(McpTool.UNDO_EDIT.id(), null);
        assertThrows(IllegalArgumentException.class, () -> status(nullValue));
    }

    private static GetServerStatus.Result status() {
        return status(allTools());
    }

    private static GetServerStatus.Result status(Map<String, Boolean> tools) {
        return new GetServerStatus.Result(
                new GetServerStatus.Builds("26.2", "Paper", "Dirt", "FAWE"),
                new GetServerStatus.Performance(20, 1),
                new GetServerStatus.PlayerSummary(0, 20, List.of()),
                List.of(),
                tools,
                new GetServerStatus.EffectiveLogging("info", 10_485_760, 5),
                new GetServerStatus.EffectiveLimits(1, 1, 1, 1, 1, 1, 1, 1, 1),
                new GetServerStatus.EffectiveEditHistory(2, 3, 4),
                new GetServerStatus.EffectiveDefaults(false, "blocks", false));
    }

    private static Map<String, Boolean> allTools() {
        return new DirtConfig.Tools(EnumSet.allOf(McpTool.class)).flags();
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

        private FixedAccess(GetServerStatus.Result status) {
            this.status = status;
        }

        @Override
        public PaperServerStatusService.HealthTarget prepareHealthCheck() {
            return () -> {};
        }

        @Override
        public GetServerStatus.Result captureStatus() {
            return this.status;
        }
    }
}
