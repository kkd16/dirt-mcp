package ca.deliyannides.dirtmcp.paper.access;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.validation.UuidV4;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Strict asynchronous client for the web service's authenticated loopback control API. */
public final class HttpAccessControlClient implements AccessControl {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final int MAXIMUM_RESPONSE_BYTES = 262_144;
    private static final int MAXIMUM_JSON_DEPTH = 32;
    private static final int MAXIMUM_SELECTOR_LENGTH = 128;
    private static final String EMPTY_BODY = "{}";

    private final URI origin;
    private final String bearerToken;
    private final Duration requestTimeout;
    private final ExecutorService executor;
    private final HttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();

    public HttpAccessControlClient(DirtConfig.AccessControl config, String bearerToken) {
        Objects.requireNonNull(config, "config");
        if (bearerToken == null || !TOKEN_PATTERN.matcher(bearerToken).matches()) {
            throw new IllegalArgumentException(
                    "DIRT_MCP_CONTROL_TOKEN must contain exactly 64 lowercase hexadecimal characters");
        }
        this.origin = URI.create(config.origin());
        this.bearerToken = bearerToken;
        this.requestTimeout = Duration.ofMillis(config.requestTimeoutMillis());
        ExecutorService newExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            this.http = buildHttpClient(config, newExecutor);
        } catch (RuntimeException | Error failure) {
            try {
                newExecutor.shutdownNow();
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        this.executor = newExecutor;
    }

    @Override
    public CompletionStage<UserPage> listUsers(int page) {
        requirePage(page);
        return get(
                "/internal/v1/access/users?page=" + page,
                (body, callId) -> decodeUserPage(body, callId, page));
    }

    @Override
    public CompletionStage<InvitationPage> listInvitations(int page) {
        requirePage(page);
        return get(
                "/internal/v1/access/invitations?page=" + page,
                (body, callId) -> decodeInvitationPage(body, callId, page));
    }

    @Override
    public CompletionStage<CreateInvitationResult> createInvitation() {
        return post("/internal/v1/access/invitations", EMPTY_BODY, this::decodeCreateInvitation);
    }

    @Override
    public CompletionStage<InvitationMutationResult> revokeInvitation(String id) {
        return post(
                "/internal/v1/access/invitations/" + pathSegment(id, "id") + "/revoke",
                EMPTY_BODY,
                this::decodeInvitationMutation);
    }

    @Override
    public CompletionStage<UserMutationResult> disableUser(String handle) {
        return userMutation(handle, "disable");
    }

    @Override
    public CompletionStage<UserMutationResult> enableUser(String handle) {
        return userMutation(handle, "enable");
    }

    @Override
    public CompletionStage<UserRecoveryResult> createUserRecovery(String handle) {
        return post(userPath(handle, "recovery"), EMPTY_BODY, this::decodeUserRecovery);
    }

    @Override
    public CompletionStage<UserMutationResult> unlinkUser(String handle) {
        return userMutation(handle, "unlink");
    }

    @Override
    public CompletionStage<MinecraftLinkChallenge> createMinecraftLinkChallenge(
            UUID minecraftUuid, String minecraftName) {
        if (minecraftUuid == null) {
            throw new IllegalArgumentException("minecraftUuid is required");
        }
        requireNonBlank(minecraftName, "minecraftName", 16);
        JsonObject body = new JsonObject();
        body.addProperty("minecraftUuid", minecraftUuid.toString());
        body.addProperty("minecraftName", minecraftName);
        return post(
                "/internal/v1/access/minecraft-links/challenges",
                body.toString(),
                this::decodeMinecraftLinkChallenge);
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            Throwable failure = null;
            try {
                this.http.shutdownNow();
            } catch (RuntimeException | Error shutdownFailure) {
                failure = shutdownFailure;
            }
            try {
                this.executor.shutdownNow();
            } catch (RuntimeException | Error shutdownFailure) {
                if (failure == null) {
                    failure = shutdownFailure;
                } else if (failure != shutdownFailure) {
                    failure.addSuppressed(shutdownFailure);
                }
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    static HttpClient buildHttpClient(DirtConfig.AccessControl config, ExecutorService executor) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .localAddress(InetAddress.ofLiteral("127.0.0.1"))
                .executor(executor)
                .build();
    }

    private CompletionStage<UserMutationResult> userMutation(String handle, String action) {
        return post(userPath(handle, action), EMPTY_BODY, this::decodeUserMutation);
    }

    private static String userPath(String handle, String action) {
        return "/internal/v1/access/users/" + pathSegment(handle, "handle") + '/' + action;
    }

    private <T> CompletionStage<T> get(String path, ResponseDecoder<T> decoder) {
        return request("GET", path, null, decoder);
    }

    private <T> CompletionStage<T> post(
            String path, String requestBody, ResponseDecoder<T> decoder) {
        return request("POST", path, requestBody, decoder);
    }

    private <T> CompletionStage<T> request(
            String method, String path, String requestBody, ResponseDecoder<T> decoder) {
        if (this.closed.get()) {
            throw unavailable("The dashboard access service is stopping.");
        }
        long deadlineNanos = System.nanoTime() + this.requestTimeout.toNanos();
        UUID callId = UUID.randomUUID();
        HttpRequest.Builder request =
                HttpRequest.newBuilder(this.origin.resolve(path))
                        .timeout(this.requestTimeout)
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + this.bearerToken)
                        .header("X-Dirt-Call-Id", callId.toString());
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json");
            request.POST(
                    HttpRequest.BodyPublishers.ofString(
                            Objects.requireNonNull(requestBody, "requestBody"),
                            StandardCharsets.UTF_8));
        } else {
            request.GET();
        }

        return this.http
                .sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                .handle(
                        (response, failure) -> {
                            if (failure != null) {
                                throw transportFailure(failure);
                            }
                            return decodeBeforeDeadline(response, callId, decoder, deadlineNanos);
                        });
    }

    private <T> T decodeBeforeDeadline(
            HttpResponse<InputStream> response,
            UUID callId,
            ResponseDecoder<T> decoder,
            long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            closeQuietly(response.body());
            throw timeoutFailure(null);
        }
        final Future<T> decoding;
        try {
            decoding = this.executor.submit(() -> decodeResponse(response, callId, decoder));
        } catch (RuntimeException failure) {
            closeQuietly(response.body());
            throw unavailable("The dashboard access service is stopping.", failure);
        }
        try {
            return decoding.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            closeQuietly(response.body());
            decoding.cancel(true);
            throw timeoutFailure(failure);
        } catch (InterruptedException failure) {
            closeQuietly(response.body());
            decoding.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable("The dashboard access request was interrupted.", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("Unexpected checked access response failure", cause);
        }
    }

    private <T> T decodeResponse(
            HttpResponse<InputStream> response, UUID callId, ResponseDecoder<T> decoder) {
        byte[] bytes = readBody(response);
        requireJsonContentType(response);
        JsonObject body = strictObject(bytes);
        if (response.statusCode() == 200) {
            try {
                return decoder.decode(body, callId);
            } catch (AccessControlException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw protocolFailure(exception);
            }
        }
        try {
            throw decodeError(response.statusCode(), body, callId);
        } catch (AccessControlException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw protocolFailure(exception);
        }
    }

    private UserPage decodeUserPage(JsonObject body, UUID callId, int requestedPage) {
        requireExactFields(
                body, Set.of("callId", "page", "pageSize", "totalItems", "totalPages", "items"));
        UUID responseCallId = verifiedCallId(body, callId);
        PageValues page = pageValues(body, requestedPage);
        List<UserSummary> items = userItems(array(body, "items"), page.pageSize());
        return new UserPage(
                responseCallId,
                page.page(),
                page.pageSize(),
                page.totalItems(),
                page.totalPages(),
                items);
    }

    private InvitationPage decodeInvitationPage(JsonObject body, UUID callId, int requestedPage) {
        requireExactFields(
                body, Set.of("callId", "page", "pageSize", "totalItems", "totalPages", "items"));
        UUID responseCallId = verifiedCallId(body, callId);
        PageValues page = pageValues(body, requestedPage);
        List<InvitationSummary> items = invitationItems(array(body, "items"), page.pageSize());
        return new InvitationPage(
                responseCallId,
                page.page(),
                page.pageSize(),
                page.totalItems(),
                page.totalPages(),
                items);
    }

    private CreateInvitationResult decodeCreateInvitation(JsonObject body, UUID callId) {
        requireExactFields(body, Set.of("callId", "invitation", "inviteUrl"));
        return new CreateInvitationResult(
                verifiedCallId(body, callId),
                invitation(object(body, "invitation")),
                uri(body, "inviteUrl"));
    }

    private InvitationMutationResult decodeInvitationMutation(JsonObject body, UUID callId) {
        requireExactFields(body, Set.of("callId", "invitation"));
        return new InvitationMutationResult(
                verifiedCallId(body, callId), invitation(object(body, "invitation")));
    }

    private UserMutationResult decodeUserMutation(JsonObject body, UUID callId) {
        requireExactFields(body, Set.of("callId", "user"));
        return new UserMutationResult(verifiedCallId(body, callId), user(object(body, "user")));
    }

    private UserRecoveryResult decodeUserRecovery(JsonObject body, UUID callId) {
        requireExactFields(body, Set.of("callId", "user", "recoveryUrl", "expiresAt"));
        return new UserRecoveryResult(
                verifiedCallId(body, callId),
                user(object(body, "user")),
                uri(body, "recoveryUrl"),
                instant(body, "expiresAt"));
    }

    private MinecraftLinkChallenge decodeMinecraftLinkChallenge(JsonObject body, UUID callId) {
        requireExactFields(body, Set.of("callId", "code", "linkUrl", "expiresAt"));
        return new MinecraftLinkChallenge(
                verifiedCallId(body, callId),
                string(body, "code", 128),
                uri(body, "linkUrl"),
                instant(body, "expiresAt"));
    }

    private static PageValues pageValues(JsonObject body, int requestedPage) {
        int page = integer(body, "page");
        int pageSize = integer(body, "pageSize");
        long totalItems = longInteger(body, "totalItems");
        int totalPages = integer(body, "totalPages");
        if (page != requestedPage
                || pageSize < 1
                || pageSize > 50
                || totalItems < 0
                || totalPages < 0) {
            throw protocolFailure();
        }
        return new PageValues(page, pageSize, totalItems, totalPages);
    }

    private static List<UserSummary> userItems(JsonArray array, int pageSize) {
        if (array.size() > pageSize) {
            throw protocolFailure();
        }
        List<UserSummary> users = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw protocolFailure();
            }
            users.add(user(element.getAsJsonObject()));
        }
        return List.copyOf(users);
    }

    private static List<InvitationSummary> invitationItems(JsonArray array, int pageSize) {
        if (array.size() > pageSize) {
            throw protocolFailure();
        }
        List<InvitationSummary> invitations = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw protocolFailure();
            }
            invitations.add(invitation(element.getAsJsonObject()));
        }
        return List.copyOf(invitations);
    }

    private static UserSummary user(JsonObject object) {
        requireExactFields(
                object, Set.of("id", "handle", "status", "minecraftAccount", "createdAt"));
        JsonElement accountElement = object.get("minecraftAccount");
        MinecraftAccount account = null;
        if (accountElement == null) {
            throw protocolFailure();
        }
        if (!accountElement.isJsonNull()) {
            if (!accountElement.isJsonObject()) {
                throw protocolFailure();
            }
            JsonObject accountObject = accountElement.getAsJsonObject();
            requireExactFields(accountObject, Set.of("uuid", "name"));
            account =
                    new MinecraftAccount(
                            uuid(accountObject, "uuid"), string(accountObject, "name", 16));
        }
        return new UserSummary(
                string(object, "id", 256),
                string(object, "handle", 256),
                userStatus(string(object, "status", 16)),
                account,
                instant(object, "createdAt"));
    }

    private static InvitationSummary invitation(JsonObject object) {
        requireExactFields(object, Set.of("id", "status", "createdAt", "expiresAt"));
        return new InvitationSummary(
                string(object, "id", 256),
                invitationStatus(string(object, "status", 16)),
                instant(object, "createdAt"),
                instant(object, "expiresAt"));
    }

    private static UserStatus userStatus(String value) {
        return switch (value) {
            case "active" -> UserStatus.ACTIVE;
            case "disabled" -> UserStatus.DISABLED;
            default -> throw protocolFailure();
        };
    }

    private static InvitationStatus invitationStatus(String value) {
        return switch (value) {
            case "pending" -> InvitationStatus.PENDING;
            case "accepted" -> InvitationStatus.ACCEPTED;
            case "revoked" -> InvitationStatus.REVOKED;
            case "expired" -> InvitationStatus.EXPIRED;
            default -> throw protocolFailure();
        };
    }

    private static AccessControlException decodeError(
            int status, JsonObject body, UUID expectedCallId) {
        requireExactFields(body, Set.of("callId", "error"));
        verifiedCallId(body, expectedCallId);
        JsonObject error = object(body, "error");
        requireExactFields(error, Set.of("code", "message"));
        String code = string(error, "code", 64);
        string(error, "message", 512);
        return switch (status) {
            case 400 ->
                    requireErrorCode(
                            code,
                            "invalid_request",
                            AccessControlException.Reason.INVALID_REQUEST,
                            "The dashboard rejected the access request.");
            case 401, 403 ->
                    requireErrorCode(
                            code,
                            "unauthorized",
                            AccessControlException.Reason.UNAUTHORIZED,
                            "Dirt access-control authentication failed.");
            case 404 ->
                    requireErrorCode(
                            code,
                            "not_found",
                            AccessControlException.Reason.NOT_FOUND,
                            "No matching access record was found.");
            case 409 ->
                    requireErrorCode(
                            code,
                            "conflict",
                            AccessControlException.Reason.CONFLICT,
                            "That access action conflicts with the current state.");
            case 500 ->
                    requireErrorCode(
                            code,
                            "internal_error",
                            AccessControlException.Reason.UNAVAILABLE,
                            "The dashboard access service failed.");
            case 503 ->
                    requireErrorCode(
                            code,
                            "unavailable",
                            AccessControlException.Reason.UNAVAILABLE,
                            "The dashboard access service is unavailable.");
            default -> throw protocolFailure();
        };
    }

    private static AccessControlException requireErrorCode(
            String actual, String expected, AccessControlException.Reason reason, String message) {
        if (!expected.equals(actual)) {
            throw protocolFailure();
        }
        return new AccessControlException(reason, message);
    }

    private static UUID verifiedCallId(JsonObject body, UUID expected) {
        UUID callId;
        try {
            callId = UuidV4.parseCanonical(string(body, "callId", 36), "callId");
        } catch (IllegalArgumentException exception) {
            throw protocolFailure(exception);
        }
        if (!expected.equals(callId)) {
            throw protocolFailure();
        }
        return callId;
    }

    private static byte[] readBody(HttpResponse<InputStream> response) {
        List<String> contentLengths = response.headers().allValues("Content-Length");
        if (contentLengths.size() > 1) {
            closeQuietly(response.body());
            throw protocolFailure();
        }
        if (!contentLengths.isEmpty()) {
            try {
                long declared = Long.parseLong(contentLengths.getFirst());
                if (declared < 0 || declared > MAXIMUM_RESPONSE_BYTES) {
                    closeQuietly(response.body());
                    throw protocolFailure();
                }
            } catch (NumberFormatException exception) {
                closeQuietly(response.body());
                throw protocolFailure(exception);
            }
        }
        try (InputStream input = response.body()) {
            byte[] bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
            if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
                throw protocolFailure();
            }
            return bytes;
        } catch (IOException exception) {
            throw unavailable("The dashboard access response could not be read.", exception);
        }
    }

    private static void requireJsonContentType(HttpResponse<?> response) {
        List<String> values = response.headers().allValues("Content-Type");
        if (values.size() != 1
                || !"application/json"
                        .equalsIgnoreCase(values.getFirst().split(";", 2)[0].trim())) {
            throw protocolFailure();
        }
    }

    private static JsonObject strictObject(byte[] bytes) {
        String json = decodeUtf8(bytes);
        validateStrictJson(json);
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw protocolFailure();
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw protocolFailure(exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw protocolFailure(exception);
        }
    }

    private static void validateStrictJson(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            validateValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw protocolFailure();
            }
        } catch (DuplicateMemberException exception) {
            throw protocolFailure(exception);
        } catch (IOException | IllegalStateException exception) {
            throw protocolFailure(exception);
        }
    }

    private static void validateValue(JsonReader reader, int depth) throws IOException {
        if (depth > MAXIMUM_JSON_DEPTH) {
            throw new IllegalStateException("JSON nesting is too deep");
        }
        switch (reader.peek()) {
            case BEGIN_ARRAY -> validateArray(reader, depth);
            case BEGIN_OBJECT -> validateObject(reader, depth);
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            case NUMBER, STRING -> reader.nextString();
            default -> throw new IllegalStateException("Expected a JSON value");
        }
    }

    private static void validateArray(JsonReader reader, int depth) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            validateValue(reader, depth + 1);
        }
        reader.endArray();
    }

    private static void validateObject(JsonReader reader, int depth) throws IOException {
        reader.beginObject();
        Set<String> fields = new HashSet<>();
        while (reader.hasNext()) {
            if (!fields.add(reader.nextName())) {
                throw new DuplicateMemberException();
            }
            validateValue(reader, depth + 1);
        }
        reader.endObject();
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonObject()) {
            throw protocolFailure();
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonArray()) {
            throw protocolFailure();
        }
        return element.getAsJsonArray();
    }

    private static String string(JsonObject parent, String name, int maximumLength) {
        JsonElement element = parent.get(name);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw protocolFailure();
        }
        String value = primitive.getAsString();
        requireNonBlank(value, name, maximumLength);
        return value;
    }

    private static int integer(JsonObject parent, String name) {
        BigDecimal value = number(parent, name);
        try {
            return value.intValueExact();
        } catch (ArithmeticException exception) {
            throw protocolFailure(exception);
        }
    }

    private static long longInteger(JsonObject parent, String name) {
        BigDecimal value = number(parent, name);
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw protocolFailure(exception);
        }
    }

    private static BigDecimal number(JsonObject parent, String name) {
        JsonElement element = parent.get(name);
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw protocolFailure();
        }
        try {
            return primitive.getAsBigDecimal();
        } catch (NumberFormatException exception) {
            throw protocolFailure(exception);
        }
    }

    private static UUID uuid(JsonObject parent, String name) {
        String value = string(parent, name, 36);
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw protocolFailure();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw protocolFailure(exception);
        }
    }

    private static Instant instant(JsonObject parent, String name) {
        try {
            return Instant.parse(string(parent, name, 64));
        } catch (DateTimeParseException exception) {
            throw protocolFailure(exception);
        }
    }

    private static URI uri(JsonObject parent, String name) {
        try {
            return URI.create(string(parent, name, 2_048));
        } catch (IllegalArgumentException exception) {
            throw protocolFailure(exception);
        }
    }

    private static void requireExactFields(JsonObject object, Set<String> expected) {
        if (!object.keySet().equals(expected)) {
            throw protocolFailure();
        }
    }

    private static void requirePage(int page) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be positive");
        }
    }

    private static String pathSegment(String value, String name) {
        requireNonBlank(value, name, MAXIMUM_SELECTOR_LENGTH);
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void requireNonBlank(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must be non-blank and at most " + maximumLength + " characters");
        }
    }

    private static AccessControlException transportFailure(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof HttpTimeoutException) {
            return timeoutFailure(cause);
        }
        return unavailable("The dashboard access service is unavailable.", cause);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException)
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static AccessControlException unavailable(String message) {
        return new AccessControlException(AccessControlException.Reason.UNAVAILABLE, message);
    }

    private static AccessControlException unavailable(String message, Throwable cause) {
        return new AccessControlException(
                AccessControlException.Reason.UNAVAILABLE, message, cause);
    }

    private static AccessControlException timeoutFailure(Throwable cause) {
        return new AccessControlException(
                AccessControlException.Reason.TIMEOUT,
                "The dashboard access service timed out.",
                cause);
    }

    private static AccessControlException protocolFailure() {
        return new AccessControlException(
                AccessControlException.Reason.PROTOCOL_ERROR,
                "The dashboard access service returned an invalid response.");
    }

    private static AccessControlException protocolFailure(Throwable cause) {
        return new AccessControlException(
                AccessControlException.Reason.PROTOCOL_ERROR,
                "The dashboard access service returned an invalid response.",
                cause);
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The response is already being rejected.
        }
    }

    @FunctionalInterface
    private interface ResponseDecoder<T> {
        T decode(JsonObject body, UUID callId);
    }

    private record PageValues(int page, int pageSize, long totalItems, int totalPages) {}

    private static final class DuplicateMemberException extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
    }
}
