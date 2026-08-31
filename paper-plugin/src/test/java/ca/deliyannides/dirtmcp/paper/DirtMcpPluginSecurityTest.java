package ca.deliyannides.dirtmcp.paper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class DirtMcpPluginSecurityTest {
    private static final String BRIDGE_TOKEN = "a".repeat(64);
    private static final String CONTROL_TOKEN = "b".repeat(64);

    @TempDir Path temporaryDirectory;

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

    @Test
    void readsUtf8CredentialsAndStripsOneFinalLineEnding() throws IOException {
        Path bridgeTokenFile = writeToken("bridge-token", BRIDGE_TOKEN + "\n");
        Path controlTokenFile = writeToken("control-token", CONTROL_TOKEN + "\r\n");

        DirtMcpPlugin.ControlCredentials credentials =
                DirtMcpPlugin.readControlCredentials(
                        bridgeTokenFile.toString(), controlTokenFile.toString());

        assertEquals(BRIDGE_TOKEN, credentials.bridgeToken());
        assertEquals(CONTROL_TOKEN, credentials.controlToken());
    }

    @Test
    void requiresBothCredentialFileInputs() throws IOException {
        Path bridgeTokenFile = writeToken("bridge-token", BRIDGE_TOKEN);
        IllegalStateException missingBridge =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.readControlCredentials(null, "unused"));
        IllegalStateException missingControl =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DirtMcpPlugin.readControlCredentials(
                                        bridgeTokenFile.toString(), "  "));

        assertTrue(missingBridge.getMessage().contains("DIRT_BRIDGE_TOKEN_FILE is required"));
        assertTrue(missingControl.getMessage().contains("DIRT_CONTROL_TOKEN_FILE is required"));
    }

    @Test
    void rejectsMissingAndNonRegularCredentialFiles() throws IOException {
        Path bridgeTokenFile = writeToken("bridge-token", BRIDGE_TOKEN);
        Path missing = this.temporaryDirectory.resolve("missing-token");

        IllegalStateException missingFailure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DirtMcpPlugin.readControlCredentials(
                                        missing.toString(), bridgeTokenFile.toString()));
        IllegalStateException directoryFailure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DirtMcpPlugin.readControlCredentials(
                                        bridgeTokenFile.toString(),
                                        this.temporaryDirectory.toString()));

        assertTrue(missingFailure.getMessage().contains("readable UTF-8 regular file"));
        assertTrue(directoryFailure.getMessage().contains("readable UTF-8 regular file"));
    }

    @Test
    void rejectsCredentialFilesThatAreNotUtf8() throws IOException {
        Path bridgeTokenFile =
                Files.write(
                        this.temporaryDirectory.resolve("bridge-token"),
                        new byte[] {(byte) 0xc3, 0x28});
        Path controlTokenFile = writeToken("control-token", CONTROL_TOKEN);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DirtMcpPlugin.readControlCredentials(
                                        bridgeTokenFile.toString(), controlTokenFile.toString()));

        assertTrue(failure.getMessage().contains("readable UTF-8 regular file"));
    }

    @ParameterizedTest
    @MethodSource("invalidTokenFileContents")
    void rejectsMalformedCredentialFileContents(String contents) throws IOException {
        Path bridgeTokenFile = writeToken("bridge-token", contents);
        Path controlTokenFile = writeToken("control-token", CONTROL_TOKEN);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DirtMcpPlugin.readControlCredentials(
                                        bridgeTokenFile.toString(), controlTokenFile.toString()));

        assertTrue(failure.getMessage().contains("64 lowercase hexadecimal"));
        if (!contents.isEmpty()) {
            assertFalse(failure.getMessage().contains(contents));
        }
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
    void rejectsCredentialReuse() throws IOException {
        Path bridgeTokenFile = writeToken("bridge-token", BRIDGE_TOKEN);
        Path controlTokenFile = writeToken("control-token", BRIDGE_TOKEN);
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                DirtMcpPlugin.readControlCredentials(
                                        bridgeTokenFile.toString(), controlTokenFile.toString()));

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

    private static Stream<String> invalidTokenFileContents() {
        return Stream.of("", "a".repeat(63), "A".repeat(64), BRIDGE_TOKEN + "\n\n");
    }

    private Path writeToken(String name, String contents) throws IOException {
        return Files.writeString(
                this.temporaryDirectory.resolve(name), contents, StandardCharsets.UTF_8);
    }

    private static void assertCredentialNotRendered(Throwable failure, String credential) {
        if (credential != null && !credential.isEmpty()) {
            assertFalse(failure.getMessage().contains(credential));
        }
    }
}
