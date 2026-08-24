package ca.deliyannides.dirtmcp.paper.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class BridgeProblemTest {
    @ParameterizedTest
    @MethodSource("problems")
    void ownsHttpAndJsonParserProblems(
            BridgeProblem problem, String expectedCode, String expectedDetails) {
        assertEquals(expectedCode, problem.code());
        assertEquals(
                JsonParser.parseString(expectedDetails),
                BridgeJson.GSON.toJsonTree(problem.details()));
    }

    @Test
    void validatesValuesAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> BridgeProblem.methodNotAllowed("PUT"));
        assertThrows(IllegalArgumentException.class, () -> BridgeProblem.operationDisabled(" "));
        assertThrows(IllegalArgumentException.class, () -> BridgeProblem.bridgeBusy(0));
        assertThrows(IllegalArgumentException.class, () -> BridgeProblem.bodyTooLarge(0));
        assertThrows(IllegalArgumentException.class, () -> BridgeProblem.duplicateJsonMember(" "));
    }

    private static Stream<Arguments> problems() {
        return Stream.of(
                Arguments.of(
                        BridgeProblem.unauthorized(),
                        "unauthorized",
                        "{reason:'authentication_failed'}"),
                Arguments.of(
                        BridgeProblem.routeNotFound(),
                        "route_not_found",
                        "{reason:'route_not_found'}"),
                Arguments.of(
                        BridgeProblem.methodNotAllowed("POST"),
                        "method_not_allowed",
                        "{allowedMethod:'POST'}"),
                Arguments.of(
                        BridgeProblem.operationDisabled("getBlocks"),
                        "operation_disabled",
                        "{operationId:'getBlocks'}"),
                Arguments.of(
                        BridgeProblem.bridgeBusy(4),
                        "bridge_busy",
                        "{maximumConcurrentRequests:4}"),
                Arguments.of(
                        BridgeProblem.unsupportedMediaType(),
                        "invalid_request",
                        "{reason:'unsupported_media_type',expected:'application/json'}"),
                Arguments.of(
                        BridgeProblem.bodyTooLarge(262144),
                        "invalid_request",
                        "{reason:'body_too_large',maximumBytes:262144}"),
                Arguments.of(
                        BridgeProblem.malformedJson(),
                        "invalid_request",
                        "{reason:'malformed_json'}"),
                Arguments.of(
                        BridgeProblem.duplicateJsonMember("include.flags"),
                        "invalid_request",
                        "{reason:'duplicate',field:'include.flags'}"),
                Arguments.of(
                        BridgeProblem.invalidValue("X-Dirt-Call-Id"),
                        "invalid_request",
                        "{reason:'invalid_value',field:'X-Dirt-Call-Id'}"));
    }
}
