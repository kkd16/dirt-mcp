package ca.deliyannides.dirtmcp.paper.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class PaperMainThreadTest {
    @Test
    void runsInlineOnThePrimaryThread() throws Exception {
        FakeScheduler scheduler = new FakeScheduler(true);
        PaperMainThread mainThread = new PaperMainThread(scheduler);

        assertEquals("value", mainThread.call(() -> "value"));
        assertEquals(0, scheduler.submissions);
    }

    @Test
    void schedulesOffThreadAndPreservesRuntimeFailures() throws Exception {
        FakeScheduler scheduler = new FakeScheduler(false);
        PaperMainThread mainThread = new PaperMainThread(scheduler);
        IllegalStateException failure = new IllegalStateException("failure");

        assertEquals("value", mainThread.call(() -> "value"));
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                mainThread.call(
                                        () -> {
                                            throw failure;
                                        })));
        assertEquals(2, scheduler.submissions);
    }

    @Test
    void wrapsCheckedFailuresAndRejectsCallsAfterClose() {
        PaperMainThread mainThread = new PaperMainThread(new FakeScheduler(false));

        PaperMainThreadException failure =
                assertThrows(
                        PaperMainThreadException.class,
                        () ->
                                mainThread.call(
                                        () -> {
                                            throw new IOException("checked");
                                        }));
        assertTrue(failure.getCause() instanceof IOException);

        mainThread.close();
        assertThrows(PaperMainThreadException.class, () -> mainThread.call(() -> "value"));
    }

    @Test
    void restoresInterruptAndCancelsScheduledWork() throws Exception {
        FutureTask<String> pending = new FutureTask<>(() -> "never run");
        PaperMainThread mainThread =
                new PaperMainThread(
                        new PaperMainThread.Scheduler() {
                            @Override
                            public boolean isPrimaryThread() {
                                return false;
                            }

                            @SuppressWarnings("unchecked")
                            @Override
                            public <T> Future<T> submit(Callable<T> action) {
                                return (Future<T>) pending;
                            }
                        });

        Thread.currentThread().interrupt();
        try {
            assertThrows(PaperMainThreadException.class, () -> mainThread.call(() -> "value"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(pending.isCancelled());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void handsOffAStartedTaskBeforeRestoringInterruption() throws Exception {
        StartedFuture<String> started = new StartedFuture<>("prepared");
        PaperMainThread mainThread =
                new PaperMainThread(
                        new PaperMainThread.Scheduler() {
                            @Override
                            public boolean isPrimaryThread() {
                                return false;
                            }

                            @Override
                            public <T> Future<T> submit(Callable<T> action) {
                                try {
                                    action.call();
                                } catch (Exception exception) {
                                    throw new AssertionError(exception);
                                }
                                @SuppressWarnings("unchecked")
                                Future<T> result = (Future<T>) started;
                                return result;
                            }
                        });

        try {
            assertEquals("prepared", mainThread.call(() -> "prepared"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(started.cancelled);
        } finally {
            Thread.interrupted();
        }
    }

    private static final class FakeScheduler implements PaperMainThread.Scheduler {
        private final boolean primary;
        private int submissions;

        private FakeScheduler(boolean primary) {
            this.primary = primary;
        }

        @Override
        public boolean isPrimaryThread() {
            return this.primary;
        }

        @Override
        public <T> Future<T> submit(Callable<T> action) {
            this.submissions++;
            CompletableFuture<T> result = new CompletableFuture<>();
            try {
                result.complete(action.call());
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
            return result;
        }
    }

    private static final class StartedFuture<T> implements Future<T> {
        private final T value;
        private boolean firstGet = true;
        private boolean cancelled;

        private StartedFuture(T value) {
            this.value = value;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            this.cancelled = true;
            return false;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public T get() throws InterruptedException {
            if (this.firstGet) {
                this.firstGet = false;
                throw new InterruptedException("request interrupted");
            }
            return this.value;
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException {
            return get();
        }
    }
}
