package ca.deliyannides.dirtmcp.paper.bridge;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/** A failure produced by the local HTTP bridge rather than a Minecraft operation. */
record BridgeProblem(String code, Map<String, Object> details) implements Serializable {
    BridgeProblem {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Bridge problem code must not be blank");
        }
        details = Map.copyOf(Objects.requireNonNull(details, "details"));
    }

    static BridgeProblem unauthorized() {
        return reason("unauthorized", "authentication_failed");
    }

    static BridgeProblem routeNotFound() {
        return reason("route_not_found", "route_not_found");
    }

    static BridgeProblem methodNotAllowed(String allowedMethod) {
        if (!"GET".equals(allowedMethod) && !"POST".equals(allowedMethod)) {
            throw new IllegalArgumentException("Allowed method must be GET or POST");
        }
        return new BridgeProblem("method_not_allowed", Map.of("allowedMethod", allowedMethod));
    }

    static BridgeProblem operationDisabled(String operationId) {
        return new BridgeProblem(
                "operation_disabled", Map.of("operationId", requireNonBlank(operationId)));
    }

    static BridgeProblem bridgeBusy(int maximumConcurrentRequests) {
        return new BridgeProblem(
                "bridge_busy",
                Map.of(
                        "maximumConcurrentRequests",
                        requirePositive(maximumConcurrentRequests, "maximumConcurrentRequests")));
    }

    static BridgeProblem unsupportedMediaType() {
        return new BridgeProblem(
                "invalid_request",
                Map.of("reason", "unsupported_media_type", "expected", "application/json"));
    }

    static BridgeProblem bodyTooLarge(int maximumBytes) {
        return new BridgeProblem(
                "invalid_request",
                Map.of(
                        "reason",
                        "body_too_large",
                        "maximumBytes",
                        requirePositive(maximumBytes, "maximumBytes")));
    }

    static BridgeProblem malformedJson() {
        return reason("invalid_request", "malformed_json");
    }

    static BridgeProblem duplicateJsonMember(String field) {
        return new BridgeProblem(
                "invalid_request", Map.of("reason", "duplicate", "field", requireNonBlank(field)));
    }

    static BridgeProblem invalidValue(String field) {
        return new BridgeProblem(
                "invalid_request",
                Map.of("reason", "invalid_value", "field", requireNonBlank(field)));
    }

    private static BridgeProblem reason(String code, String reason) {
        return new BridgeProblem(code, Map.of("reason", reason));
    }

    private static String requireNonBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Bridge problem value must not be blank");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
