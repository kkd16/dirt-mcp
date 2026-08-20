package ca.deliyannides.dirtmcp.paper.platform;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperMainThread implements MainThread {
    private final JavaPlugin plugin;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PaperMainThread(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public <T> T call(MainThread.CheckedSupplier<T> action) throws PaperMainThreadException {
        Objects.requireNonNull(action, "action");
        if (this.closed.get()) {
            throw new PaperMainThreadException("Paper access is stopping");
        }
        if (Bukkit.isPrimaryThread()) {
            return invoke(action);
        }

        Future<T> future =
                this.plugin.getServer().getScheduler().callSyncMethod(this.plugin, action::get);
        try {
            return future.get();
        } catch (InterruptedException exception) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new PaperMainThreadException("Paper access was interrupted", exception);
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
}
