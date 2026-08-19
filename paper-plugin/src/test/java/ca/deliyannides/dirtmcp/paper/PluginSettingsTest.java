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

        assertEquals(new Bridge(8_765, 0, 0, 8_192, 32), settings.bridge());
        assertEquals(
                new Limits(
                        1_000_000,
                        250_000,
                        32_768,
                        10_000,
                        10_000,
                        32_768,
                        2_048,
                        10_000,
                        20,
                        32_768,
                        20),
                settings.limits());
        assertEquals(new Defaults(false, "blocks", false, false), settings.defaults());
    }

    @Test
    void appliesTheEnvironmentPortOverrideWithoutChangingOtherSettings() {
        PluginSettings settings = PluginSettings.load(defaultConfiguration(), " 9876 ");

        assertEquals(9_876, settings.bridge().port());
        assertEquals(8_192, settings.bridge().maxRequestBytes());
    }

    @Test
    void rejectsMissingInvalidAndInconsistentValues() {
        YamlConfiguration missing = defaultConfiguration();
        missing.set("bridge.max-request-bytes", null);
        YamlConfiguration invalidMode = defaultConfiguration();
        invalidMode.set("defaults.exact-inspection-mode", "summary");
        YamlConfiguration inconsistentResults = defaultConfiguration();
        inconsistentResults.set("limits.default-view-results", 10_001);

        assertThrows(IllegalArgumentException.class, () -> PluginSettings.load(missing, null));
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.load(invalidMode, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> PluginSettings.load(inconsistentResults, null));
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
