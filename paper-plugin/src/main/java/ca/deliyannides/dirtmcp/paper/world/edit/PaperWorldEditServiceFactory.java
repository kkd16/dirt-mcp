package ca.deliyannides.dirtmcp.paper.world.edit;

import ca.deliyannides.dirtmcp.paper.config.DirtConfig;
import ca.deliyannides.dirtmcp.paper.logging.DirtLog;
import ca.deliyannides.dirtmcp.paper.platform.MainThread;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper composition adapter for the Dirt-owned world-edit application service. */
public final class PaperWorldEditServiceFactory {
    private PaperWorldEditServiceFactory() {}

    public static WorldEditService create(
            JavaPlugin plugin,
            MainThread mainThread,
            DirtConfig.Limits limits,
            DirtConfig.EditHistory history,
            DirtLog log) {
        Objects.requireNonNull(limits, "limits");
        return new WorldEditService(
                new PaperFaweEditPlatform(
                        Objects.requireNonNull(plugin, "plugin"),
                        Objects.requireNonNull(mainThread, "mainThread"),
                        limits.maxChangedBlocks(),
                        Objects.requireNonNull(log, "log")),
                limits.maxRegionVolume(),
                limits.maxEditTouchedChunks(),
                limits.maxBlockStatePatterns(),
                limits.maxPaletteEntries(),
                limits.maxChangedBlocks(),
                Objects.requireNonNull(history, "history"));
    }
}
