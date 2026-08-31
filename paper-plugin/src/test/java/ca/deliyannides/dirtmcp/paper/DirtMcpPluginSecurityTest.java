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
        writeToken("bridge-token", BRIDGE_TOKEN + "\n");
        writeToken("control-token", CONTROL_TOKEN + "\r\n");

        DirtMcpPlugin.ControlCredentials credentials =
                DirtMcpPlugin.readControlCredentials(this.temporaryDirectory);

        assertEquals(BRIDGE_TOKEN, credentials.bridgeToken());
        assertEquals(CONTROL_TOKEN, credentials.controlToken());
    }

    @Test
    void requiresBothCredentialFiles() throws IOException {
        IllegalStateException missingBridge =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.readControlCredentials(this.temporaryDirectory));
        writeToken("bridge-token", BRIDGE_TOKEN);
        IllegalStateException missingControl =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.readControlCredentials(this.temporaryDirectory));

        assertTrue(missingBridge.getMessage().contains("bridge token file"));
        assertTrue(missingControl.getMessage().contains("control token file"));
    }

    @Test
    void rejectsMissingAndNonRegularCredentialFiles() throws IOException {
        writeToken("control-token", CONTROL_TOKEN);

        IllegalStateException missingFailure =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.readControlCredentials(this.temporaryDirectory));
        Files.createDirectory(this.temporaryDirectory.resolve("secrets").resolve("bridge-token"));
        IllegalStateException directoryFailure =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.readControlCredentials(this.temporaryDirectory));

        assertTrue(missingFailure.getMessage().contains("readable UTF-8 regular file"));
        assertTrue(directoryFailure.getMessage().contains("readable UTF-8 regular file"));
    }

    @Test
    void rejectsCredentialFilesThatAreNotUtf8() throws IOException {
        writeToken("control-token", CONTROL_TOKEN);
        Files.write(
                this.temporaryDirectory.resolve("secrets").resolve("bridge-token"),
                new byte[] {(byte) 0xc3, 0x28});

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.readControlCredentials(this.temporaryDirectory));

        assertTrue(failure.getMessage().contains("readable UTF-8 regular file"));
    }

    @Test
    void doesNotReadLegacyCredentialFilesFromTheDataDirectoryRoot() throws IOException {
        Files.writeString(
                this.temporaryDirectory.resolve("bridge-token"),
                BRIDGE_TOKEN,
                StandardCharsets.UTF_8);
        Files.writeString(
                this.temporaryDirectory.resolve("control-token"),
                CONTROL_TOKEN,
                StandardCharsets.UTF_8);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.readControlCredentials(this.temporaryDirectory));

        assertTrue(failure.getMessage().contains("bridge token file"));
        assertTrue(failure.getMessage().contains("readable UTF-8 regular file"));
    }

    @ParameterizedTest
    @MethodSource("invalidTokenFileContents")
    void rejectsMalformedCredentialFileContents(String contents) throws IOException {
        writeToken("bridge-token", contents);
        writeToken("control-token", CONTROL_TOKEN);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.readControlCredentials(this.temporaryDirectory));

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
        writeToken("bridge-token", BRIDGE_TOKEN);
        writeToken("control-token", BRIDGE_TOKEN);
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> DirtMcpPlugin.readControlCredentials(this.temporaryDirectory));

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
        Path secrets = Files.createDirectories(this.temporaryDirectory.resolve("secrets"));
        return Files.writeString(secrets.resolve(name), contents, StandardCharsets.UTF_8);
    }

    private static void assertCredentialNotRendered(Throwable failure, String credential) {
        if (credential != null && !credential.isEmpty()) {
            assertFalse(failure.getMessage().contains(credential));
        }
    }
}
