package ca.deliyannides.dirtmcp.paper.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class RequestBodyReader implements AutoCloseable {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final int timeoutSeconds;

    RequestBodyReader(int timeoutSeconds) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("Request-body timeout must be positive");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    byte[] read(InputStream input, int maximumBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        Future<byte[]> read;
        try {
            read = this.executor.submit(() -> input.readNBytes(maximumBytes + 1));
        } catch (RejectedExecutionException exception) {
            throw new IOException("The bridge is stopping", exception);
        }
        try {
            return read.get(this.timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            read.cancel(true);
            closeInputAsync(input);
            throw new BodyTimeoutException(
                    "Request body was not received within " + this.timeoutSeconds + " seconds");
        } catch (InterruptedException exception) {
            read.cancel(true);
            closeInputAsync(input);
            Thread.currentThread().interrupt();
            throw new IOException("Request-body reading was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Could not read request body", cause);
        }
    }

    @Override
    public void close() {
        this.executor.shutdownNow();
    }

    private void closeInputAsync(InputStream input) {
        try {
            this.executor.submit(
                    () -> {
                        try {
                            input.close();
                        } catch (IOException ignored) {
                            // The exchange is already being aborted.
                        }
                    });
        } catch (RejectedExecutionException ignored) {
            // Server shutdown closes the owning exchange.
        }
    }

    static final class BodyTimeoutException extends IOException {
        @Serial private static final long serialVersionUID = 1L;

        private BodyTimeoutException(String message) {
            super(message);
        }
    }
}
