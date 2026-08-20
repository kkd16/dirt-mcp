package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.logging.LogContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class BridgeServer implements AutoCloseable {
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";

    private final DirtConfig config;
    private final BridgeDispatcher dispatcher;
    private final DirtLog log;

    private HttpServer server;
    private ExecutorService executor;
    private boolean closed;

    public BridgeServer(
            DirtConfig config, String bearerToken, List<BridgeEndpoint> endpoints, DirtLog log) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        BearerAuthenticator authenticator =
                new BearerAuthenticator(
                        Objects.requireNonNull(bearerToken, "bearerToken"),
                        config.bridge().minimumTokenBytes());
        this.dispatcher =
                new BridgeDispatcher(
                        Objects.requireNonNull(endpoints, "endpoints"),
                        authenticator,
                        config.bridge().maxConcurrentRequests(),
                        config.limits().maxRequestBytes(),
                        config.bridge().requestBodyTimeoutSeconds(),
                        log);
    }

    public synchronized void start() throws IOException {
        if (this.closed) {
            throw new IllegalStateException("Dirt MCP bridge has been closed");
        }
        if (this.server != null) {
            throw new IllegalStateException("Dirt MCP bridge is already running");
        }

        ExecutorService newExecutor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer newServer = null;
        try {
            newServer =
                    HttpServer.create(
                            new InetSocketAddress(LOOPBACK_ADDRESS, this.config.bridge().port()),
                            this.config.bridge().backlog());
            newServer.createContext("/v1/", this.dispatcher::handle);
            newServer.createContext(
                    "/v1",
                    exchange -> {
                        if ("/v1".equals(exchange.getRequestURI().getRawPath())) {
                            this.dispatcher.handle(exchange);
                        } else {
                            exchange.sendResponseHeaders(404, -1);
                            exchange.close();
                        }
                    });
            newServer.setExecutor(newExecutor);
            newServer.start();
        } catch (IOException | RuntimeException exception) {
            if (newServer != null) {
                newServer.stop(0);
            }
            newExecutor.shutdownNow();
            throw exception;
        }

        this.executor = newExecutor;
        this.server = newServer;
        int port = boundPort();
        String message = "Dirt MCP bridge listening on http://" + LOOPBACK_ADDRESS + ':' + port;
        LogContext context =
                LogContext.of("host", LOOPBACK_ADDRESS)
                        .with("port", port)
                        .with("backlog", this.config.bridge().backlog());
        this.log.debug("bridge", "bridge.started", message, context);
    }

    public synchronized int boundPort() {
        if (this.server == null) {
            throw new IllegalStateException("Dirt MCP bridge is not running");
        }
        return this.server.getAddress().getPort();
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        HttpServer runningServer = this.server;
        ExecutorService runningExecutor = this.executor;
        this.server = null;
        this.executor = null;
        try {
            if (runningServer != null) {
                // Plugin disable runs on Paper's main thread. Stop accepting work and
                // interrupt handlers before waiting, so a handler awaiting a Paper task
                // cannot deadlock shutdown.
                runningServer.stop(0);
                if (runningExecutor != null) {
                    runningExecutor.shutdownNow();
                    try {
                        boolean terminated =
                                runningExecutor.awaitTermination(
                                        this.config.bridge().shutdownDelaySeconds(),
                                        TimeUnit.SECONDS);
                        if (!terminated) {
                            LogContext context =
                                    LogContext.of(
                                                    "shutdown_delay_seconds",
                                                    this.config.bridge().shutdownDelaySeconds())
                                            .with("resources_may_be_retained", true);
                            this.log.warning(
                                    "bridge",
                                    "bridge.shutdown_deadline_exceeded",
                                    "Dirt MCP request workers exceeded the shutdown deadline; "
                                            + "Paper shutdown will continue",
                                    context);
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            this.dispatcher.close();
            LogContext context = LogContext.empty();
            this.log.debug("bridge", "bridge.stopped", "Dirt MCP bridge stopped", context);
        }
    }
}
