package ca.deliyannides.dirtmcp.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.deliyannides.dirtmcp.paper.bridge.BridgeOperation;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class DirtConfigLoaderTest {
    @Test
    void loadsTheCompleteShippedConfiguration() {
        DirtConfig config = DirtConfigLoader.load(defaultConfiguration(), null, null);

        assertEquals(
                new DirtConfig.Bridge(
                        8_765, 5, 5, 32, 4, 1_048_576, List.of(BridgeOperation.values())),
                config.bridge());
        assertEquals(
                new DirtConfig.AccessControl("http://127.0.0.1:3000", 2_000, 5_000),
                config.accessControl());
        assertEquals(
                new DirtConfig.Logging(DirtConfig.ConsoleLogLevel.INFO, 10_485_760, 5),
                config.logging());
        assertEquals(
                new DirtConfig.Limits(
                        1_048_576, 512, 128, 256, 64, 256, 262_144, 262_144, 131_072, 4_096, 2_048,
                        10, 8_192),
                config.limits());
        assertEquals(new DirtConfig.EditHistory(50, 200, 2_621_440), config.editHistory());
    }

    @Test
    void acceptsAnExplicitlyEmptyOperationAllowlist() {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set("bridge.allowed-operations", List.of());

        assertTrue(
                DirtConfigLoader.load(configuration, null, null)
                        .bridge()
                        .allowedOperations()
                        .isEmpty());
    }

    @Test
    void keepsInspectionAndEditChunkBudgetsIndependent() {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set("limits.max-inspection-touched-chunks", 513);

        assertEquals(
                513,
                DirtConfigLoader.load(configuration, null, null)
                        .limits()
                        .maxInspectionTouchedChunks());
    }

    @Test
    void ignoresBundledDefaultsWhenCheckingExplicitValues() {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.setDefaults(defaultConfiguration());
        configuration.options().copyDefaults(true);
        configuration.set("bridge.max-request-bytes", null);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> DirtConfigLoader.load(configuration, null, null));

        assertEquals("bridge.max-request-bytes is required", error.getMessage());
    }

    @ParameterizedTest
    @MethodSource("validPortOverrides")
    void acceptsValidPortOverrides(String override, int expected) {
        assertEquals(
                expected,
                DirtConfigLoader.load(defaultConfiguration(), override, null).bridge().port());
    }

    @ParameterizedTest
    @MethodSource("invalidPortOverrides")
    void rejectsInvalidPortOverrides(String override) {
        assertThrows(
                IllegalArgumentException.class,
                () -> DirtConfigLoader.load(defaultConfiguration(), override, null));
    }

    @ParameterizedTest
    @MethodSource("validAccessControlOverrides")
    void acceptsOnlyExplicitLoopbackAccessControlOverrides(String override, String expected) {
        assertEquals(
                expected,
                DirtConfigLoader.load(defaultConfiguration(), null, override)
                        .accessControl()
                        .origin());
    }

    @ParameterizedTest
    @MethodSource("invalidAccessControlOverrides")
    void rejectsUnsafeAccessControlOverrides(String override) {
        assertThrows(
                IllegalArgumentException.class,
                () -> DirtConfigLoader.load(defaultConfiguration(), null, override));
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurationValues")
    void rejectsMissingUnknownInvalidAndInconsistentConfiguration(String path, Object value) {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set(path, value);

        assertThrows(
                IllegalArgumentException.class,
                () -> DirtConfigLoader.load(configuration, null, null));
    }

    private static Stream<Arguments> validPortOverrides() {
        return Stream.of(
                Arguments.of(null, 8_765),
                Arguments.of("", 8_765),
                Arguments.of(" 9876 ", 9_876),
                Arguments.of("1", 1),
                Arguments.of("65535", 65_535));
    }

    private static Stream<String> invalidPortOverrides() {
        return Stream.of("0", "65536", "1.5", "paper");
    }

    private static Stream<Arguments> validAccessControlOverrides() {
        return Stream.of(
                Arguments.of(null, "http://127.0.0.1:3000"),
                Arguments.of("", "http://127.0.0.1:3000"),
                Arguments.of("http://127.0.0.1", "http://127.0.0.1"),
                Arguments.of("http://127.0.0.1:4321/", "http://127.0.0.1:4321"));
    }

    private static Stream<String> invalidAccessControlOverrides() {
        return Stream.of(
                "https://127.0.0.1:3000",
                "http://localhost:3000",
                "http://0.0.0.0:3000",
                "http://127.0.0.1:0",
                "http://127.0.0.1:65536",
                "http://127.0.0.1:3000/path",
                "http://127.0.0.1:3000?query=true",
                "http://127.0.0.1:3000#fragment");
    }

    private static Stream<Arguments> invalidConfigurationValues() {
        return Stream.of(
                Arguments.of("bridge.allowed-operations", null),
                Arguments.of("bridge.allowed-operations", "pingServer"),
                Arguments.of("bridge.allowed-operations", List.of("unknownOperation")),
                Arguments.of("bridge.allowed-operations", List.of("pingServer", "pingServer")),
                Arguments.of("bridge.allowed-operations", Arrays.asList("pingServer", null)),
                Arguments.of("bridge.unknown", 1),
                Arguments.of("logging.console-level", null),
                Arguments.of("logging.console-level", "warn"),
                Arguments.of("logging.detail-file-max-bytes", 0),
                Arguments.of("logging.detail-file-retained-files", 1),
                Arguments.of("logging.detail-file-retained-files", 101),
                Arguments.of("bridge.port", 0),
                Arguments.of("bridge.shutdown-delay-seconds", 0),
                Arguments.of("bridge.shutdown-delay-seconds", 31),
                Arguments.of("bridge.request-body-timeout-seconds", 0),
                Arguments.of("bridge.max-concurrent-requests", 0),
                Arguments.of("bridge.max-concurrent-inspections", 0),
                Arguments.of("bridge.max-concurrent-inspections", 33),
                Arguments.of("bridge.max-request-bytes", 0),
                Arguments.of("bridge.max-request-bytes", 67_108_865),
                Arguments.of("access-control.url", null),
                Arguments.of("access-control.url", "http://localhost:3000"),
                Arguments.of("access-control.connect-timeout-millis", 0),
                Arguments.of("access-control.connect-timeout-millis", 30_001),
                Arguments.of("access-control.request-timeout-millis", 1_999),
                Arguments.of("access-control.request-timeout-millis", 30_001),
                Arguments.of("access-control.unknown", true),
                Arguments.of("limits.max-region-volume", 0),
                Arguments.of("limits.max-edit-touched-chunks", 0),
                Arguments.of("limits.max-inspection-touched-chunks", 0),
                Arguments.of("limits.max-perspective-touched-chunks", 0),
                Arguments.of("limits.max-block-state-patterns", 65),
                Arguments.of("limits.max-palette-entries", 257),
                Arguments.of("limits.max-changed-blocks", 1_048_577),
                Arguments.of("limits.max-inspection-volume", 1_048_577),
                Arguments.of("limits.max-perspective-ray-distance-budget", 0),
                Arguments.of("limits.max-inspection-results", 262_145),
                Arguments.of("limits.max-perspective-rays", 131_073),
                Arguments.of("limits.max-commands-per-request", 0),
                Arguments.of("limits.max-command-feedback-characters", 0),
                Arguments.of("edit-history.max-entries-per-world", null),
                Arguments.of("edit-history.max-entries-per-world", 201),
                Arguments.of("edit-history.max-entries-total", 49),
                Arguments.of("edit-history.max-retained-changed-blocks", 262_143),
                Arguments.of("limits.not-a-limit", 20));
    }

    private static YamlConfiguration defaultConfiguration() {
        var stream = DirtConfigLoaderTest.class.getResourceAsStream("/config.yml");
        if (stream == null) {
            throw new IllegalStateException("Packaged config.yml is unavailable");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read packaged config.yml", exception);
        }
    }
}
