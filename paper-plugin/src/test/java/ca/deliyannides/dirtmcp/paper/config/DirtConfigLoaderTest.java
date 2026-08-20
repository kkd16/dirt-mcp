package ca.deliyannides.dirtmcp.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
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

        assertEquals(new DirtConfig.Bridge(8_765, 5, 5, 32, 2), config.bridge());
        assertEquals(EnumSet.allOf(McpTool.class), config.tools().enabled());
        assertEquals(McpTool.values().length, config.tools().flags().size());
        assertTrue(config.tools().flags().values().stream().allMatch(Boolean::booleanValue));
        assertEquals(
                new DirtConfig.Logging(DirtConfig.ConsoleLogLevel.INFO, 10_485_760, 5),
                config.logging());
        assertEquals(
                new DirtConfig.Limits(262_144, 262_144, 256, 32, 64, 65_536, 16_384, 512, 2_048),
                config.limits());
        assertEquals(new DirtConfig.EditHistory(20, 100, 1_310_720), config.editHistory());
        assertEquals(new DirtConfig.Defaults(false, "blocks", false), config.defaults());
    }

    @Test
    void missingToolSectionAndKeysDefaultToDisabled() {
        YamlConfiguration withoutSection = defaultConfiguration();
        withoutSection.set("tools", null);

        DirtConfig noTools = DirtConfigLoader.load(withoutSection, null);

        assertTrue(noTools.tools().enabled().isEmpty());
        assertTrue(noTools.tools().flags().values().stream().noneMatch(Boolean::booleanValue));

        YamlConfiguration withoutKey = defaultConfiguration();
        withoutKey.set("tools.undo_edit", null);

        DirtConfig oneMissing = DirtConfigLoader.load(withoutKey, null);

        assertFalse(oneMissing.tools().isEnabled(McpTool.UNDO_EDIT));
        assertEquals(McpTool.values().length - 1, oneMissing.tools().enabled().size());
    }

    @Test
    void ignoresBundledDefaultsWhenCheckingExplicitValues() {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set("tools", null);
        configuration.setDefaults(defaultConfiguration());
        configuration.options().copyDefaults(true);

        DirtConfig config = DirtConfigLoader.load(configuration, null);

        assertTrue(config.tools().enabled().isEmpty());

        configuration.set("limits.max-request-bytes", null);
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> DirtConfigLoader.load(configuration, null));
        assertEquals("limits.max-request-bytes is required", error.getMessage());
    }

    @Test
    void explicitFalseDisablesOnlyThatTool() {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set("tools.fill_region", false);

        DirtConfig config = DirtConfigLoader.load(configuration, null);

        assertFalse(config.tools().isEnabled(McpTool.FILL_REGION));
        assertTrue(config.tools().isEnabled(McpTool.SET_BLOCKS));
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
                Arguments.of("tools", true),
                Arguments.of("tools.fill_region", "true"),
                Arguments.of("tools.not_a_tool", true),
                Arguments.of("logging.console-level", null),
                Arguments.of("logging.console-level", "warn"),
                Arguments.of("logging.detail-file-max-bytes", 0),
                Arguments.of("logging.detail-file-retained-files", 0),
                Arguments.of("logging.detail-file-retained-files", 1),
                Arguments.of("logging.detail-file-retained-files", 101),
                Arguments.of("bridge.port", 0),
                Arguments.of("bridge.shutdown-delay-seconds", -1),
                Arguments.of("bridge.shutdown-delay-seconds", 0),
                Arguments.of("bridge.shutdown-delay-seconds", 31),
                Arguments.of("bridge.request-body-timeout-seconds", 0),
                Arguments.of("bridge.max-concurrent-requests", 0),
                Arguments.of("bridge.max-concurrent-inspections", 0),
                Arguments.of("bridge.max-concurrent-inspections", 33),
                Arguments.of("limits.max-request-bytes", Integer.MAX_VALUE),
                Arguments.of("limits.max-region-volume", 0),
                Arguments.of("limits.max-touched-chunks", 0),
                Arguments.of("limits.max-inspection-touched-chunks", 0),
                Arguments.of("limits.max-inspection-touched-chunks", 257),
                Arguments.of("limits.max-block-state-patterns", 0),
                Arguments.of("limits.max-block-state-patterns", 65),
                Arguments.of("limits.max-changed-blocks", 262_145),
                Arguments.of("limits.max-inspection-volume", 262_145),
                Arguments.of("limits.default-inspection-results", 2_049),
                Arguments.of("limits.max-inspection-results", 16_385),
                Arguments.of("edit-history.max-entries-per-world", null),
                Arguments.of("edit-history.max-entries-per-world", 0),
                Arguments.of("edit-history.max-entries-per-world", 101),
                Arguments.of("edit-history.max-entries-total", 0),
                Arguments.of("edit-history.max-entries-total", 19),
                Arguments.of("edit-history.max-retained-changed-blocks", 0),
                Arguments.of("edit-history.max-retained-changed-blocks", 65_535),
                Arguments.of("limits.not-a-limit", 20),
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
