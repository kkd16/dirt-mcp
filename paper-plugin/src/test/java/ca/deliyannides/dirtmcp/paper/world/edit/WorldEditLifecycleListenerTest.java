package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.jupiter.api.Test;

final class WorldEditLifecycleListenerTest {
    @Test
    void invalidatesHistoryOnlyAfterAnUncancelledWorldUnload() throws NoSuchMethodException {
        EventHandler handler =
                WorldEditLifecycleListener.class
                        .getDeclaredMethod("onWorldUnload", WorldUnloadEvent.class)
                        .getAnnotation(EventHandler.class);

        assertNotNull(handler);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }
}
