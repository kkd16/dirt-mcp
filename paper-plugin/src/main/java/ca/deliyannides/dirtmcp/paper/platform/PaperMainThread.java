package ca.deliyannides.dirtmcp.paper.platform;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperMainThread implements MainThread {
    private final Scheduler scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PaperMainThread(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.scheduler =
                new Scheduler() {
                    @Override
                    public boolean isPrimaryThread() {
                        return Bukkit.isPrimaryThread();
                    }

                    @Override
                    public <T> Future<T> submit(Callable<T> action) {
                        return plugin.getServer().getScheduler().callSyncMethod(plugin, action);
                    }
                };
    }

    PaperMainThread(Scheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public <T> T call(MainThread.CheckedSupplier<T> action) throws PaperMainThreadException {
        Objects.requireNonNull(action, "action");
        if (this.closed.get()) {
            throw new PaperMainThreadException("Paper access is stopping");
        }
        if (this.scheduler.isPrimaryThread()) {
            return invoke(action);
        }

        AtomicBoolean taskClaimed = new AtomicBoolean();
        Future<T> future =
                this.scheduler.submit(
                        () -> {
                            if (!taskClaimed.compareAndSet(false, true)) {
                                throw new IllegalStateException(
                                        "Paper action was abandoned before it started");
                            }
                            return action.get();
                        });
        try {
            return result(future);
        } catch (InterruptedException exception) {
            if (taskClaimed.compareAndSet(false, true)) {
                future.cancel(false);
                Thread.currentThread().interrupt();
                throw new PaperMainThreadException("Paper access was interrupted", exception);
            }
            try {
                while (true) {
                    try {
                        return result(future);
                    } catch (InterruptedException ignored) {
                        // The Paper action already started, so its result must be handed off.
                    }
                }
            } finally {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        this.closed.set(true);
    }

    private static <T> T invoke(MainThread.CheckedSupplier<T> action)
            throws PaperMainThreadException {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PaperMainThreadException("Paper main-thread task failed", exception);
        }
    }

    private static <T> T result(Future<T> future)
            throws InterruptedException, PaperMainThreadException {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            throw new PaperMainThreadException(
                    "Paper main-thread task failed", exception.getCause());
        }
    }

    interface Scheduler {
        boolean isPrimaryThread();

        <T> Future<T> submit(Callable<T> action);
    }
}
