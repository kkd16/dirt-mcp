package ca.deliyannides.dirtmcp.paper.world.inspection;

import ca.deliyannides.dirtmcp.paper.error.ErrorDetails;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.entity.Player;

final class BukkitPlayerResolver {
    private BukkitPlayerResolver() {}

    static Player resolve(Server server, String selector) throws OperationException {
        UUID uniqueId = canonicalUuid(selector);
        Player player =
                uniqueId == null ? server.getPlayerExact(selector) : server.getPlayer(uniqueId);
        if (player == null) {
            throw new OperationException(
                    OperationFailure.PLAYER_NOT_FOUND,
                    "Player is not online: " + selector,
                    new ErrorDetails.PlayerNotFound(selector));
        }
        return player;
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equals(value.toLowerCase(Locale.ROOT)) ? uuid : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
