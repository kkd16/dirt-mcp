package ca.deliyannides.dirtmcp.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class DirtConfigLoaderTest {
    @Test
    void loadsTheCompleteShippedConfiguration() {
        DirtConfig config = DirtConfigLoader.load(defaultConfiguration(), null);

        assertEquals(new DirtConfig.Bridge(8_765, 0, 5, 32, 32), config.bridge());
        assertEquals(
                new DirtConfig.Limits(
                        262_144, 262_144, 256, 65_536, 16_384, 512, 2_048, 10, 8_192, 20),
                config.limits());
        assertEquals(new DirtConfig.Defaults(false, "blocks", false), config.defaults());
    }

    @ParameterizedTest
    @MethodSource("validPortOverrides")
    void acceptsValidPortOverrides(String override, int expected) {
        assertEquals(
                expected, DirtConfigLoader.load(defaultConfiguration(), override).bridge().port());
    }

    @ParameterizedTest
    @MethodSource("invalidPortOverrides")
    void rejectsInvalidPortOverrides(String override) {
        assertThrows(
                IllegalArgumentException.class,
                () -> DirtConfigLoader.load(defaultConfiguration(), override));
    }

    @ParameterizedTest
    @MethodSource("invalidConfigurationValues")
    void rejectsMissingUnknownInvalidAndInconsistentConfiguration(String path, Object value) {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set(path, value);

        assertThrows(
                IllegalArgumentException.class, () -> DirtConfigLoader.load(configuration, null));
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

    private static Stream<Arguments> invalidConfigurationValues() {
        return Stream.of(
                Arguments.of("limits.max-request-bytes", null),
                Arguments.of("bridge.unknown", 1),
                Arguments.of("bridge.port", 0),
                Arguments.of("bridge.backlog", -1),
                Arguments.of("bridge.shutdown-delay-seconds", -1),
                Arguments.of("bridge.minimum-token-bytes", 0),
                Arguments.of("bridge.max-concurrent-requests", 0),
                Arguments.of("limits.max-request-bytes", Integer.MAX_VALUE),
                Arguments.of("limits.max-region-volume", 0),
                Arguments.of("limits.max-touched-chunks", 0),
                Arguments.of("limits.max-changed-blocks", 262_145),
                Arguments.of("limits.max-inspection-volume", 262_145),
                Arguments.of("limits.default-inspection-results", 2_049),
                Arguments.of("limits.max-inspection-results", 16_385),
                Arguments.of("limits.max-commands-per-request", 0),
                Arguments.of("limits.max-command-feedback-characters", 0),
                Arguments.of("limits.undo-history-per-world", -1),
                Arguments.of("defaults.region-blocks-format", "summary"),
                Arguments.of("defaults.region-blocks-format", " "),
                Arguments.of("defaults.region-blocks-include-air", "false"),
                Arguments.of("defaults.edit-dry-run", "false"));
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
