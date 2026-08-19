package ca.deliyannides.dirtmcp.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.deliyannides.dirtmcp.paper.PluginSettings.Bridge;
import ca.deliyannides.dirtmcp.paper.PluginSettings.Defaults;
import ca.deliyannides.dirtmcp.paper.PluginSettings.Limits;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class PluginSettingsTest {
    @Test
    void loadsEveryShippedConfigurationValue() {
        PluginSettings settings = PluginSettings.load(defaultConfiguration(), null);

        assertEquals(new Bridge(8_765, 0, 0, 32), settings.bridge());
        assertEquals(
                new Limits(
                        262_144,
                        262_144,
                        65_536,
                        16_384,
                        512,
                        2_048,
                        10,
                        8_192,
                        20),
                settings.limits());
        assertEquals(new Defaults(false, "blocks", false), settings.defaults());
    }

    @Test
    void appliesTheEnvironmentPortOverrideWithoutChangingOtherSettings() {
        PluginSettings settings = PluginSettings.load(defaultConfiguration(), " 9876 ");

        assertEquals(9_876, settings.bridge().port());
        assertEquals(262_144, settings.limits().maxRequestBytes());
    }

    @Test
    void rejectsMissingInvalidAndInconsistentValues() {
        YamlConfiguration missing = defaultConfiguration();
        missing.set("limits.max-request-bytes", null);
        YamlConfiguration invalidMode = defaultConfiguration();
        invalidMode.set("defaults.region-blocks-format", "summary");
        YamlConfiguration inconsistentResults = defaultConfiguration();
        inconsistentResults.set("limits.default-inspection-results", 2_049);
        YamlConfiguration inconsistentChangedBlocks = defaultConfiguration();
        inconsistentChangedBlocks.set("limits.max-changed-blocks", 262_145);
        YamlConfiguration inconsistentInspection = defaultConfiguration();
        inconsistentInspection.set("limits.max-inspection-results", 16_385);

        assertThrows(IllegalArgumentException.class, () -> PluginSettings.load(missing, null));
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.load(invalidMode, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> PluginSettings.load(inconsistentResults, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> PluginSettings.load(inconsistentChangedBlocks, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> PluginSettings.load(inconsistentInspection, null));
    }

    private static YamlConfiguration defaultConfiguration() {
        var stream = PluginSettingsTest.class.getResourceAsStream("/config.yml");
        if (stream == null) {
            throw new IllegalStateException("Packaged config.yml is unavailable");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not read packaged config.yml", exception);
        }
    }
}
