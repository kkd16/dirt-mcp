package ca.deliyannides.dirtmcp.paper.world.inspection;

import java.util.Objects;
import java.util.UUID;

/** Resolved identity of one online Paper player. */
public record PlayerIdentity(String name, UUID uuid) {
    public PlayerIdentity {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(uuid, "uuid");
    }
}
