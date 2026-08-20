package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

final class FaweEditExecutorTest {
    @AfterEach
    void clearInterruption() {
        Thread.interrupted();
    }

    @Test
    void preparedRollbackTemporarilyClearsAndThenRestoresInterruption() {
        Thread.currentThread().interrupt();
        AtomicInteger calls = new AtomicInteger();

        FaweEditExecutor.preserveInterruption(
                () -> {
                    assertFalse(Thread.currentThread().isInterrupted());
                    calls.incrementAndGet();
                });

        assertEquals(1, calls.get());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void preparedRollbackPreservesAConcurrentInterruption() {
        FaweEditExecutor.preserveInterruption(() -> Thread.currentThread().interrupt());

        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void storedUndoDisposesItsChangeSetExactlyOnce() {
        AtomicInteger deletes = new AtomicInteger();
        ChangeSet changeSet = changeSet(deletes, false);
        FaweEditExecutor.StoredUndo undo =
                new FaweEditExecutor.StoredUndo(
                        changeSet,
                        2,
                        List.of(new ChunkPosition(1, 2)),
                        DirtLog.consoleOnly(
                                NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR));

        assertEquals(2, undo.changedBlockCount());
        assertEquals(List.of(new ChunkPosition(1, 2)), undo.chunks());
        assertSame(changeSet, undo.changeSet());

        undo.close();
        undo.close();

        assertEquals(1, deletes.get());
        assertThrows(IllegalStateException.class, undo::changeSet);
    }

    @Test
    void storedUndoContainsDisposalFailuresAndRemainsClosed() {
        AtomicInteger deletes = new AtomicInteger();
        AtomicReference<LogRecord> warning = new AtomicReference<>();
        Handler handler =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        warning.set(record);
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };
        DirtLog log =
                DirtLog.withDetailHandler(
                        NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR, handler);
        FaweEditExecutor.StoredUndo undo =
                new FaweEditExecutor.StoredUndo(
                        changeSet(deletes, true), 1, List.of(new ChunkPosition(0, 0)), log);

        undo.close();
        undo.close();

        assertEquals(1, deletes.get());
        assertEquals(Level.WARNING, warning.get().getLevel());
        assertEquals("edit.undo_data_disposal_failed", warning.get().getLoggerName());
        LogContext context = (LogContext) warning.get().getParameters()[0];
        assertEquals(1L, context.values().get("changed_block_count"));
        assertThrows(IllegalStateException.class, undo::changeSet);
    }

    private static ChangeSet changeSet(AtomicInteger deletes, boolean failDelete) {
        return (ChangeSet)
                Proxy.newProxyInstance(
                        ChangeSet.class.getClassLoader(),
                        new Class<?>[] {ChangeSet.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("delete")) {
                                deletes.incrementAndGet();
                                if (failDelete) {
                                    throw new IllegalStateException("delete failed");
                                }
                                return null;
                            }
                            if (method.getName().equals("longSize")) {
                                return 1L;
                            }
                            if (method.getName().equals("toString")) {
                                return "test change set";
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }
}
