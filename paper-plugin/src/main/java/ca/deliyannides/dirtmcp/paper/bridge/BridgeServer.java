package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BridgeServer implements AutoCloseable {
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";

    private final DirtConfig config;
    private final BridgeDispatcher dispatcher;
    private final Logger logger;

    private HttpServer server;
    private ExecutorService executor;
    private boolean closed;

    public BridgeServer(
            DirtConfig config, String bearerToken, List<BridgeEndpoint> endpoints, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
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
                        logger);
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
        if (this.logger.isLoggable(Level.INFO)) {
            this.logger.info(
                    "Dirt MCP bridge listening on http://" + LOOPBACK_ADDRESS + ':' + boundPort());
        }
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
                        if (!terminated && this.logger.isLoggable(Level.WARNING)) {
                            this.logger.warning(
                                    "Dirt MCP request workers exceeded the shutdown deadline; "
                                            + "Paper shutdown will continue without releasing "
                                            + "resources still owned by active edits.");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            this.dispatcher.close();
            if (this.logger.isLoggable(Level.INFO)) {
                this.logger.info("Dirt MCP bridge stopped.");
            }
        }
    }
}
