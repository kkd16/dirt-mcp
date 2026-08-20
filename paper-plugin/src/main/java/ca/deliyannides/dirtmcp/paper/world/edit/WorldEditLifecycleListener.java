package ca.deliyannides.dirtmcp.paper.world.edit;

import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

public final class WorldEditLifecycleListener implements Listener {
    private final FaweWorldEditor editor;

    public WorldEditLifecycleListener(FaweWorldEditor editor) {
        this.editor = Objects.requireNonNull(editor, "editor");
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        this.editor.invalidateWorld(event.getWorld().getUID());
    }
}
