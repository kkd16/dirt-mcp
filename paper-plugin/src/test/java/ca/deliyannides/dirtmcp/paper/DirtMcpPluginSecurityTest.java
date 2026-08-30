package ca.deliyannides.dirtmcp.paper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class DirtMcpPluginSecurityTest {
    private static final String BRIDGE_TOKEN = "a".repeat(64);
    private static final String CONTROL_TOKEN = "b".repeat(64);

    @Test
    void requiresMinecraftOnlineModeBeforeStarting() {
        assertDoesNotThrow(() -> DirtMcpPlugin.requireOnlineMode(true));

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class, () -> DirtMcpPlugin.requireOnlineMode(false));
        assertTrue(failure.getMessage().contains("online-mode=true"));
        assertTrue(failure.getMessage().contains("secure account linking"));
    }

    @Test
    void acceptsDistinctStrongControlCredentialsWithoutRenderingThem() {
        DirtMcpPlugin.ControlCredentials credentials =
                DirtMcpPlugin.validateControlCredentials(BRIDGE_TOKEN, CONTROL_TOKEN);

        assertEquals(BRIDGE_TOKEN, credentials.bridgeToken());
        assertEquals(CONTROL_TOKEN, credentials.controlToken());
        assertEquals("ControlCredentials[redacted]", credentials.toString());
        assertFalse(credentials.toString().contains(BRIDGE_TOKEN));
        assertFalse(credentials.toString().contains(CONTROL_TOKEN));
    }

    @ParameterizedTest
    @MethodSource("invalidCredentials")
    void rejectsMissingOrMalformedDedicatedCredentials(String bridgeToken, String controlToken) {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.validateControlCredentials(bridgeToken, controlToken));

        assertCredentialNotRendered(failure, bridgeToken);
        assertCredentialNotRendered(failure, controlToken);
    }

    @Test
    void rejectsCredentialReuse() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.validateControlCredentials(BRIDGE_TOKEN, BRIDGE_TOKEN));

        assertTrue(failure.getMessage().contains("must be distinct"));
        assertFalse(failure.getMessage().contains(BRIDGE_TOKEN));
    }

    private static Stream<Arguments> invalidCredentials() {
        return Stream.of(
                Arguments.of(null, CONTROL_TOKEN),
                Arguments.of("", CONTROL_TOKEN),
                Arguments.of("a".repeat(63), CONTROL_TOKEN),
                Arguments.of("A".repeat(64), CONTROL_TOKEN),
                Arguments.of(BRIDGE_TOKEN, null),
                Arguments.of(BRIDGE_TOKEN, ""),
                Arguments.of(BRIDGE_TOKEN, "b".repeat(65)),
                Arguments.of(BRIDGE_TOKEN, "B".repeat(64)));
    }

    private static void assertCredentialNotRendered(Throwable failure, String credential) {
        if (credential != null && !credential.isEmpty()) {
            assertFalse(failure.getMessage().contains(credential));
        }
    }
}
