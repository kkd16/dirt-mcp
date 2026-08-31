package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import java.io.IOException;
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
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        ChangeSet changeSet = changeSet(2, closes, deletes, false);
        FaweEditExecutor.StoredUndo undo =
                FaweEditExecutor.StoredUndo.finalizedOrNull(
                        changeSet,
                        List.of(new ChunkPosition(1, 2)),
                        DirtLog.consoleOnly(
                                NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR));

        assertNotNull(undo);
        assertEquals(2, undo.changedBlockCount());
        assertEquals(List.of(new ChunkPosition(1, 2)), undo.chunks());
        assertSame(changeSet, undo.changeSet());
        assertEquals(1, closes.get());

        undo.close();
        undo.close();

        assertEquals(1, deletes.get());
        assertThrows(IllegalStateException.class, undo::changeSet);
    }

    @Test
    void storedUndoContainsDisposalFailuresAndRemainsClosed() {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        AtomicReference<LogRecord> warning = new AtomicReference<>();
        Handler handler =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        warning.set(record);
                        throw new IllegalStateException("logging failed");
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
                FaweEditExecutor.StoredUndo.finalizedOrNull(
                        changeSet(1, closes, deletes, true), List.of(new ChunkPosition(0, 0)), log);

        assertNotNull(undo);
        undo.close();
        undo.close();

        assertEquals(1, deletes.get());
        assertEquals(1, closes.get());
        assertEquals(Level.WARNING, warning.get().getLevel());
        assertEquals("edit.undo_data_disposal_failed", warning.get().getLoggerName());
        LogContext context = (LogContext) warning.get().getParameters()[0];
        assertEquals(1L, context.values().get("changed_block_count"));
        assertThrows(IllegalStateException.class, undo::changeSet);
    }

    @Test
    void storedUndoRejectsUnfinalizedChangeSets() {
        ChangeSet changeSet =
                (ChangeSet)
                        Proxy.newProxyInstance(
                                ChangeSet.class.getClassLoader(),
                                new Class<?>[] {ChangeSet.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("close")) {
                                        throw new IOException("close failed");
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                FaweEditExecutor.StoredUndo.finalizedOrNull(
                                        changeSet,
                                        List.of(),
                                        DirtLog.consoleOnly(
                                                NOPLogger.NOP_LOGGER,
                                                DirtConfig.ConsoleLogLevel.ERROR)));

        assertTrue(failure.getCause() instanceof IOException);
    }

    @Test
    void finalizedUndoDrainsHistoryBeforeReadingItsSize() {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        ChangeSet changeSet =
                (ChangeSet)
                        Proxy.newProxyInstance(
                                ChangeSet.class.getClassLoader(),
                                new Class<?>[] {ChangeSet.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("close")) {
                                        closes.incrementAndGet();
                                        return null;
                                    }
                                    if (method.getName().equals("longSize")) {
                                        return closes.get() == 0 ? 0L : 2L;
                                    }
                                    if (method.getName().equals("delete")) {
                                        deletes.incrementAndGet();
                                        return null;
                                    }
                                    if (method.getName().equals("toString")) {
                                        return "delayed change set";
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });

        FaweEditExecutor.StoredUndo undo =
                FaweEditExecutor.StoredUndo.finalizedOrNull(
                        changeSet,
                        List.of(new ChunkPosition(1, 2)),
                        DirtLog.consoleOnly(
                                NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR));

        assertNotNull(undo);
        assertEquals(1, closes.get());
        assertEquals(2, undo.changedBlockCount());
        undo.close();
        assertEquals(1, deletes.get());
    }

    @Test
    void finalizedUndoDisposesEmptyChangeSets() {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();

        FaweEditExecutor.StoredUndo undo =
                FaweEditExecutor.StoredUndo.finalizedOrNull(
                        changeSet(0, closes, deletes, false),
                        List.of(new ChunkPosition(1, 2)),
                        DirtLog.consoleOnly(
                                NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR));

        assertNull(undo);
        assertEquals(1, closes.get());
        assertEquals(1, deletes.get());
    }

    @Test
    void pendingUndoRetriesFinalizationBeforeUse() {
        AtomicInteger closes = new AtomicInteger();
        ChangeSet changeSet =
                (ChangeSet)
                        Proxy.newProxyInstance(
                                ChangeSet.class.getClassLoader(),
                                new Class<?>[] {ChangeSet.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("close")) {
                                        if (closes.getAndIncrement() == 0) {
                                            throw new IOException("close failed");
                                        }
                                        return null;
                                    }
                                    if (method.getName().equals("longSize")) {
                                        return 1L;
                                    }
                                    if (method.getName().equals("toString")) {
                                        return "pending change set";
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        FaweEditExecutor.StoredUndo undo =
                FaweEditExecutor.StoredUndo.pending(
                        changeSet,
                        1,
                        List.of(),
                        DirtLog.consoleOnly(
                                NOPLogger.NOP_LOGGER, DirtConfig.ConsoleLogLevel.ERROR));

        assertThrows(IllegalStateException.class, undo::finalizeForUse);
        undo.finalizeForUse();

        assertEquals(2, closes.get());
        assertSame(changeSet, undo.changeSet());
    }

    private static ChangeSet changeSet(
            long size, AtomicInteger closes, AtomicInteger deletes, boolean failDelete) {
        return (ChangeSet)
                Proxy.newProxyInstance(
                        ChangeSet.class.getClassLoader(),
                        new Class<?>[] {ChangeSet.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("close")) {
                                closes.incrementAndGet();
                                return null;
                            }
                            if (method.getName().equals("delete")) {
                                deletes.incrementAndGet();
                                if (failDelete) {
                                    throw new IllegalStateException("delete failed");
                                }
                                return null;
                            }
                            if (method.getName().equals("longSize")) {
                                return size;
                            }
                            if (method.getName().equals("toString")) {
                                return "test change set";
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }
}
