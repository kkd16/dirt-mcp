package ca.deliyannides.dirtmcp.paper.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class HttpAccessControlClientTest {
    private static final String TOKEN =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final UUID MINECRAFT_UUID =
            UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final String CREATED_AT = "2026-08-30T12:00:00Z";
    private static final String EXPIRES_AT = "2026-08-30T12:10:00Z";
    private static final Pattern CALL_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    @Test
    void sendsEveryRouteWithDedicatedAuthenticationAndDecodesExactSuccessShapes() throws Exception {
        try (ControlServer server =
                        new ControlServer(HttpAccessControlClientTest::successResponse);
                HttpAccessControlClient client = client(server)) {
            AccessControl.UserPage users = client.listUsers(2).toCompletableFuture().join();
            AccessControl.InvitationPage invitations =
                    client.listInvitations(3).toCompletableFuture().join();
            AccessControl.CreateInvitationResult created =
                    client.createInvitation(MINECRAFT_UUID, "Builder").toCompletableFuture().join();
            AccessControl.InvitationMutationResult revoked =
                    client.revokeInvitation("invite /?#").toCompletableFuture().join();
            AccessControl.UserMutationResult disabled =
                    client.disableUser("Builder /?#").toCompletableFuture().join();
            AccessControl.UserMutationResult enabled =
                    client.enableUser("Builder /?#").toCompletableFuture().join();
            AccessControl.UserRecoveryResult recovery =
                    client.createUserRecovery("Builder /?#").toCompletableFuture().join();
            AccessControl.UserMutationResult unlinked =
                    client.unlinkUser("Builder /?#").toCompletableFuture().join();
            AccessControl.MinecraftLinkChallenge challenge =
                    client.createMinecraftLinkChallenge(MINECRAFT_UUID, "Builder")
                            .toCompletableFuture()
                            .join();

            assertEquals(2, users.page());
            assertEquals(20, users.pageSize());
            assertEquals(21, users.totalItems());
            assertEquals(2, users.totalPages());
            assertEquals("Builder", users.items().getFirst().username());
            assertEquals(AccessControl.UserStatus.ACTIVE, users.items().getFirst().status());
            assertEquals(MINECRAFT_UUID, users.items().getFirst().minecraftUuid());
            assertEquals(Instant.parse(CREATED_AT), users.items().getFirst().createdAt());
            assertEquals(3, invitations.page());
            assertEquals(
                    AccessControl.InvitationStatus.PENDING,
                    invitations.items().getFirst().status());
            assertEquals("invite_1", created.invitation().id());
            assertEquals(
                    URI.create("https://dashboard.example/invite/private"), created.inviteUrl());
            assertEquals(AccessControl.InvitationStatus.REVOKED, revoked.invitation().status());
            assertEquals(AccessControl.UserStatus.DISABLED, disabled.user().status());
            assertEquals(AccessControl.UserStatus.ACTIVE, enabled.user().status());
            assertEquals("Builder", recovery.user().username());
            assertEquals(
                    URI.create("https://dashboard.example/recover/private"),
                    recovery.recoveryUrl());
            assertEquals(Instant.parse(EXPIRES_AT), recovery.expiresAt());
            assertEquals(null, unlinked.user().minecraftUuid());
            assertEquals("LINK-1234", challenge.code());
            assertEquals(URI.create("https://dashboard.example/link/private"), challenge.linkUrl());

            List<RecordedRequest> requests = server.requests();
            assertEquals(9, requests.size());
            assertRequest(requests.get(0), "GET", "/internal/v1/access/users", "page=2", null);
            assertRequest(
                    requests.get(1), "GET", "/internal/v1/access/invitations", "page=3", null);
            assertRequest(
                    requests.get(2),
                    "POST",
                    "/internal/v1/access/invitations",
                    null,
                    "{\"minecraftUuid\":\"" + MINECRAFT_UUID + "\",\"minecraftName\":\"Builder\"}");
            assertRequest(
                    requests.get(3),
                    "POST",
                    "/internal/v1/access/invitations/invite%20%2F%3F%23/revoke",
                    null,
                    "{}");
            assertRequest(
                    requests.get(4),
                    "POST",
                    "/internal/v1/access/users/Builder%20%2F%3F%23/disable",
                    null,
                    "{}");
            assertRequest(
                    requests.get(5),
                    "POST",
                    "/internal/v1/access/users/Builder%20%2F%3F%23/enable",
                    null,
                    "{}");
            assertRequest(
                    requests.get(6),
                    "POST",
                    "/internal/v1/access/users/Builder%20%2F%3F%23/recovery",
                    null,
                    "{}");
            assertRequest(
                    requests.get(7),
                    "POST",
                    "/internal/v1/access/users/Builder%20%2F%3F%23/unlink",
                    null,
                    "{}");
            assertRequest(
                    requests.get(8),
                    "POST",
                    "/internal/v1/access/minecraft-links/challenges",
                    null,
                    "{\"minecraftUuid\":\"" + MINECRAFT_UUID + "\",\"minecraftName\":\"Builder\"}");
            assertEquals(
                    (long) requests.size(),
                    requests.stream().map(RecordedRequest::callId).distinct().count());
        }
    }

    @ParameterizedTest
    @MethodSource("serviceErrors")
    void mapsOnlyExactServiceErrorsToSanitizedTypedFailures(
            int status,
            String code,
            AccessControlException.Reason expectedReason,
            String expectedMessage)
            throws Exception {
        try (ControlServer server =
                        new ControlServer(
                                exchange ->
                                        json(
                                                exchange,
                                                status,
                                                errorBody(
                                                        callId(exchange),
                                                        code,
                                                        "untrusted service detail")));
                HttpAccessControlClient client = client(server)) {
            AccessControlException failure = failure(client.listUsers(1));

            assertEquals(expectedReason, failure.reason());
            assertEquals(expectedMessage, failure.getMessage());
            assertFalse(failure.getMessage().contains("untrusted service detail"));
        }
    }

    @Test
    void rejectsUnknownDuplicateMalformedAndMismatchedJson() throws Exception {
        List<InvalidResponse> responses =
                List.of(
                        new InvalidResponse(
                                "unknown field",
                                callId ->
                                        (userPage(callId, 1).replaceFirst("}$", ",\"extra\":1}"))
                                                .getBytes(StandardCharsets.UTF_8),
                                "application/json"),
                        new InvalidResponse(
                                "duplicate field",
                                callId ->
                                        ("{\"callId\":\""
                                                        + callId
                                                        + "\",\"callId\":\""
                                                        + callId
                                                        + "\",\"page\":1,\"pageSize\":20,"
                                                        + "\"totalItems\":0,\"totalPages\":0,\"items\":[]}")
                                                .getBytes(StandardCharsets.UTF_8),
                                "application/json"),
                        new InvalidResponse(
                                "mismatched call ID",
                                ignored ->
                                        userPage("323e4567-e89b-42d3-a456-426614174000", 1)
                                                .getBytes(StandardCharsets.UTF_8),
                                "application/json"),
                        new InvalidResponse(
                                "uppercase call ID",
                                callId ->
                                        userPage(callId.toUpperCase(Locale.ROOT), 1)
                                                .getBytes(StandardCharsets.UTF_8),
                                "application/json"),
                        new InvalidResponse(
                                "inconsistent page totals",
                                callId ->
                                        userPage(callId, 1)
                                                .replace("\"totalPages\":2", "\"totalPages\":1")
                                                .getBytes(StandardCharsets.UTF_8),
                                "application/json"),
                        new InvalidResponse(
                                "malformed UTF-8",
                                ignored -> new byte[] {(byte) 0xc3, 0x28},
                                "application/json"),
                        new InvalidResponse(
                                "wrong content type",
                                callId -> userPage(callId, 1).getBytes(StandardCharsets.UTF_8),
                                "text/plain"));

        for (InvalidResponse response : responses) {
            try (ControlServer server =
                            new ControlServer(
                                    exchange ->
                                            respond(
                                                    exchange,
                                                    200,
                                                    response.contentType(),
                                                    response.body().apply(callId(exchange))));
                    HttpAccessControlClient client = client(server)) {
                assertEquals(
                        AccessControlException.Reason.PROTOCOL_ERROR,
                        failure(client.listUsers(1)).reason(),
                        response.name());
            }
        }
    }

    @Test
    void rejectsUnsafeSecretUrlsAndUnexpectedErrorShapes() throws Exception {
        try (ControlServer server =
                        new ControlServer(
                                exchange -> {
                                    String callId = callId(exchange);
                                    String body =
                                            "{\"callId\":\""
                                                    + callId
                                                    + "\",\"invitation\":"
                                                    + invitation("pending")
                                                    + ",\"inviteUrl\":\"http://evil.example/secret\"}";
                                    json(exchange, 200, body);
                                });
                HttpAccessControlClient client = client(server)) {
            assertEquals(
                    AccessControlException.Reason.PROTOCOL_ERROR,
                    failure(client.createInvitation(MINECRAFT_UUID, "Builder")).reason());
        }

        try (ControlServer server =
                        new ControlServer(
                                exchange ->
                                        json(
                                                exchange,
                                                401,
                                                errorBody(
                                                        callId(exchange),
                                                        "not_the_expected_code",
                                                        "detail")));
                HttpAccessControlClient client = client(server)) {
            assertEquals(
                    AccessControlException.Reason.PROTOCOL_ERROR,
                    failure(client.listUsers(1)).reason());
        }
    }

    @Test
    void rejectsAnInvitationResponseForADifferentMinecraftIdentity() throws Exception {
        UUID otherUuid = UUID.fromString("323e4567-e89b-42d3-a456-426614174000");
        for (String mismatchedInvitation :
                List.of(
                        invitation("pending")
                                .replace(MINECRAFT_UUID.toString(), otherUuid.toString()),
                        invitation("pending").replace("Builder", "OtherPlayer"))) {
            try (ControlServer server =
                            new ControlServer(
                                    exchange ->
                                            json(
                                                    exchange,
                                                    200,
                                                    "{\"callId\":\""
                                                            + callId(exchange)
                                                            + "\",\"invitation\":"
                                                            + mismatchedInvitation
                                                            + ",\"inviteUrl\":\"https://dashboard.example/invite/private\"}"));
                    HttpAccessControlClient client = client(server)) {
                assertEquals(
                        AccessControlException.Reason.PROTOCOL_ERROR,
                        failure(client.createInvitation(MINECRAFT_UUID, "Builder")).reason());
            }
        }
    }

    @Test
    void capsResponsesAndNeverFollowsRedirects() throws Exception {
        AtomicBoolean redirectTargetCalled = new AtomicBoolean();
        try (ControlServer server =
                        new ControlServer(
                                exchange -> {
                                    if (exchange.getRequestURI()
                                            .getPath()
                                            .equals("/redirect-target")) {
                                        redirectTargetCalled.set(true);
                                        json(exchange, 200, userPage(callId(exchange), 1));
                                        return;
                                    }
                                    exchange.getResponseHeaders()
                                            .set("Location", "/redirect-target");
                                    json(
                                            exchange,
                                            302,
                                            errorBody(callId(exchange), "internal_error", "x"));
                                });
                HttpAccessControlClient client = client(server)) {
            assertEquals(
                    AccessControlException.Reason.PROTOCOL_ERROR,
                    failure(client.listUsers(1)).reason());
            assertFalse(redirectTargetCalled.get());
        }

        try (ControlServer server =
                        new ControlServer(
                                exchange -> {
                                    byte[] oversized = new byte[262_145];
                                    java.util.Arrays.fill(oversized, (byte) ' ');
                                    respond(exchange, 200, "application/json", oversized);
                                });
                HttpAccessControlClient client = client(server)) {
            assertEquals(
                    AccessControlException.Reason.PROTOCOL_ERROR,
                    failure(client.listUsers(1)).reason());
        }
    }

    @Test
    void enforcesTimeoutsAndMapsConnectionFailures() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ControlServer server =
                        new ControlServer(
                                exchange -> {
                                    exchange.getResponseHeaders()
                                            .set("Content-Type", "application/json");
                                    exchange.sendResponseHeaders(200, 0);
                                    entered.countDown();
                                    try {
                                        release.await(2, TimeUnit.SECONDS);
                                    } catch (InterruptedException exception) {
                                        Thread.currentThread().interrupt();
                                    }
                                    try (var output = exchange.getResponseBody()) {
                                        output.write(
                                                userPage(callId(exchange), 1)
                                                        .getBytes(StandardCharsets.UTF_8));
                                    }
                                });
                HttpAccessControlClient client = client(server, 50, 100)) {
            CompletionStage<AccessControl.UserPage> pending = client.listUsers(1);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(AccessControlException.Reason.TIMEOUT, failure(pending).reason());
        } finally {
            release.countDown();
        }

        int unusedPort;
        try (java.net.ServerSocket socket =
                new java.net.ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = socket.getLocalPort();
        }
        try (HttpAccessControlClient client = client(unusedPort, 100, 200)) {
            assertEquals(
                    AccessControlException.Reason.UNAVAILABLE,
                    failure(client.listUsers(1)).reason());
        }
    }

    @Test
    void validatesCredentialsInputsAndLifecycleBeforeSending() throws Exception {
        try (ControlServer server =
                new ControlServer(HttpAccessControlClientTest::successResponse)) {
            DirtConfig.AccessControl config = config(server.port(), 100, 200);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new HttpAccessControlClient(config, null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new HttpAccessControlClient(config, "A".repeat(64)));

            HttpAccessControlClient client = client(server);
            try {
                assertThrows(IllegalArgumentException.class, () -> client.listUsers(0));
                assertThrows(IllegalArgumentException.class, () -> client.disableUser(" "));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.createInvitation(null, "Builder"));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.createInvitation(MINECRAFT_UUID, " "));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.createInvitation(MINECRAFT_UUID, "not-valid"));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.createMinecraftLinkChallenge(null, "Builder"));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.createMinecraftLinkChallenge(MINECRAFT_UUID, " "));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.createMinecraftLinkChallenge(MINECRAFT_UUID, "a".repeat(17)));
                client.close();
                AccessControlException stopped =
                        assertThrows(AccessControlException.class, () -> client.listUsers(1));
                assertEquals(AccessControlException.Reason.UNAVAILABLE, stopped.reason());
            } finally {
                client.close();
            }
        }
    }

    @Test
    void forcesTheControlTransportToBypassProcessWideProxies() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpClient http =
                    HttpAccessControlClient.buildHttpClient(config(3_000, 100, 200), executor);
            try {
                assertEquals(HttpClient.Builder.NO_PROXY, http.proxy().orElseThrow());
                assertEquals(HttpClient.Redirect.NEVER, http.followRedirects());
            } finally {
                http.shutdownNow();
            }
        }
    }

    private static Stream<Arguments> serviceErrors() {
        return Stream.of(
                Arguments.of(
                        400,
                        "invalid_request",
                        AccessControlException.Reason.INVALID_REQUEST,
                        "The dashboard rejected the access request."),
                Arguments.of(
                        401,
                        "unauthorized",
                        AccessControlException.Reason.UNAUTHORIZED,
                        "Dirt access-control authentication failed."),
                Arguments.of(
                        403,
                        "unauthorized",
                        AccessControlException.Reason.UNAUTHORIZED,
                        "Dirt access-control authentication failed."),
                Arguments.of(
                        404,
                        "not_found",
                        AccessControlException.Reason.NOT_FOUND,
                        "No matching access record was found."),
                Arguments.of(
                        409,
                        "conflict",
                        AccessControlException.Reason.CONFLICT,
                        "That access action conflicts with the current state."),
                Arguments.of(
                        500,
                        "internal_error",
                        AccessControlException.Reason.UNAVAILABLE,
                        "The dashboard access service failed."));
    }

    private static HttpAccessControlClient client(ControlServer server) {
        return client(server, 500, 2_000);
    }

    private static HttpAccessControlClient client(
            ControlServer server, int connectTimeoutMillis, int requestTimeoutMillis) {
        return client(server.port(), connectTimeoutMillis, requestTimeoutMillis);
    }

    private static HttpAccessControlClient client(
            int port, int connectTimeoutMillis, int requestTimeoutMillis) {
        return new HttpAccessControlClient(
                config(port, connectTimeoutMillis, requestTimeoutMillis), TOKEN);
    }

    private static DirtConfig.AccessControl config(
            int port, int connectTimeoutMillis, int requestTimeoutMillis) {
        return new DirtConfig.AccessControl(
                "http://127.0.0.1:" + port, connectTimeoutMillis, requestTimeoutMillis);
    }

    private static AccessControlException failure(CompletionStage<?> request) {
        CompletionException completion =
                assertThrows(CompletionException.class, () -> request.toCompletableFuture().join());
        assertTrue(completion.getCause() instanceof AccessControlException);
        return (AccessControlException) completion.getCause();
    }

    private static void assertRequest(
            RecordedRequest request,
            String method,
            String path,
            String query,
            String expectedBody) {
        assertEquals(method, request.method());
        assertEquals(path, request.rawPath());
        assertEquals(query, request.rawQuery());
        assertEquals(expectedBody == null ? "" : expectedBody, request.body());
        assertEquals("Bearer " + TOKEN, request.authorization());
        assertEquals("application/json", request.accept());
        assertTrue(CALL_ID.matcher(request.callId()).matches());
        if (expectedBody == null) {
            assertEquals(null, request.contentType());
        } else {
            assertEquals("application/json", request.contentType());
        }
    }

    private static void successResponse(HttpExchange exchange) throws IOException {
        String callId = callId(exchange);
        String path = exchange.getRequestURI().getPath();
        String rawPath = exchange.getRequestURI().getRawPath();
        String body;
        if (path.equals("/internal/v1/access/users")) {
            int page =
                    Integer.parseInt(
                            exchange.getRequestURI().getQuery().substring("page=".length()));
            body = userPage(callId, page);
        } else if (path.equals("/internal/v1/access/invitations")
                && exchange.getRequestMethod().equals("GET")) {
            int page =
                    Integer.parseInt(
                            exchange.getRequestURI().getQuery().substring("page=".length()));
            body = invitationPage(callId, page);
        } else if (rawPath.equals("/internal/v1/access/invitations")) {
            body =
                    "{\"callId\":\""
                            + callId
                            + "\",\"invitation\":"
                            + invitation("pending")
                            + ",\"inviteUrl\":\"https://dashboard.example/invite/private\"}";
        } else if (rawPath.endsWith("/revoke")) {
            body = "{\"callId\":\"" + callId + "\",\"invitation\":" + invitation("revoked") + "}";
        } else if (rawPath.endsWith("/recovery")) {
            body =
                    "{\"callId\":\""
                            + callId
                            + "\",\"user\":"
                            + user("active", true)
                            + ",\"recoveryUrl\":\"https://dashboard.example/recover/private\","
                            + "\"expiresAt\":\""
                            + EXPIRES_AT
                            + "\"}";
        } else if (rawPath.endsWith("/disable")) {
            body = userMutation(callId, "disabled", true);
        } else if (rawPath.endsWith("/enable")) {
            body = userMutation(callId, "active", true);
        } else if (rawPath.endsWith("/unlink")) {
            body = userMutation(callId, "active", false);
        } else if (path.equals("/internal/v1/access/minecraft-links/challenges")) {
            body =
                    "{\"callId\":\""
                            + callId
                            + "\",\"code\":\"LINK-1234\","
                            + "\"linkUrl\":\"https://dashboard.example/link/private\","
                            + "\"expiresAt\":\""
                            + EXPIRES_AT
                            + "\"}";
        } else {
            throw new AssertionError("Unexpected route: " + rawPath);
        }
        json(exchange, 200, body);
    }

    private static String userPage(String callId, int page) {
        return "{\"callId\":\""
                + callId
                + "\",\"page\":"
                + page
                + ",\"pageSize\":20,\"totalItems\":21,\"totalPages\":2,\"items\":["
                + user("active", true)
                + "]}";
    }

    private static String invitationPage(String callId, int page) {
        return "{\"callId\":\""
                + callId
                + "\",\"page\":"
                + page
                + ",\"pageSize\":20,\"totalItems\":41,\"totalPages\":3,\"items\":["
                + invitation("pending")
                + "]}";
    }

    private static String userMutation(String callId, String status, boolean linked) {
        return "{\"callId\":\"" + callId + "\",\"user\":" + user(status, linked) + "}";
    }

    private static String user(String status, boolean linked) {
        String minecraftUuid = linked ? "\"" + MINECRAFT_UUID + "\"" : "null";
        return "{\"id\":\"usr_1\",\"username\":\"Builder\",\"status\":\""
                + status
                + "\",\"minecraftUuid\":"
                + minecraftUuid
                + ",\"createdAt\":\""
                + CREATED_AT
                + "\"}";
    }

    private static String invitation(String status) {
        return "{\"id\":\"invite_1\",\"status\":\""
                + status
                + "\",\"minecraftAccount\":{\"uuid\":\""
                + MINECRAFT_UUID
                + "\",\"name\":\"Builder\"},\"createdAt\":\""
                + CREATED_AT
                + "\",\"expiresAt\":\""
                + EXPIRES_AT
                + "\"}";
    }

    private static String errorBody(String callId, String code, String message) {
        return "{\"callId\":\""
                + callId
                + "\",\"error\":{\"code\":\""
                + code
                + "\",\"message\":\""
                + message
                + "\"}}";
    }

    private static String callId(HttpExchange exchange) {
        return exchange.getRequestHeaders().getFirst("X-Dirt-Call-Id");
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        respond(
                exchange,
                status,
                "application/json; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    private record InvalidResponse(
            String name, Function<String, byte[]> body, String contentType) {}

    private record RecordedRequest(
            String method,
            String rawPath,
            String rawQuery,
            String body,
            String authorization,
            String accept,
            String contentType,
            String callId) {}

    private static final class ControlServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final List<RecordedRequest> requests = new ArrayList<>();

        private ControlServer(Responder responder) throws IOException {
            this.server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            this.server.setExecutor(this.executor);
            this.server.createContext(
                    "/",
                    exchange -> {
                        synchronized (this.requests) {
                            this.requests.add(
                                    new RecordedRequest(
                                            exchange.getRequestMethod(),
                                            exchange.getRequestURI().getRawPath(),
                                            exchange.getRequestURI().getRawQuery(),
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8),
                                            exchange.getRequestHeaders().getFirst("Authorization"),
                                            exchange.getRequestHeaders().getFirst("Accept"),
                                            exchange.getRequestHeaders().getFirst("Content-Type"),
                                            callId(exchange)));
                        }
                        responder.respond(exchange);
                    });
            this.server.start();
        }

        private int port() {
            return this.server.getAddress().getPort();
        }

        private List<RecordedRequest> requests() {
            synchronized (this.requests) {
                return List.copyOf(this.requests);
            }
        }

        @Override
        public void close() {
            this.server.stop(0);
            this.executor.shutdownNow();
        }
    }
}
