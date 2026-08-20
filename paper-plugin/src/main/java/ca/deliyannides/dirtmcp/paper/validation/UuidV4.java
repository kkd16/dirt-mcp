package ca.deliyannides.dirtmcp.paper.validation;

import java.util.Objects;
import java.util.UUID;

public final class UuidV4 {
    private UuidV4() {}

    public static UUID parseCanonical(String value, String name) {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid(name, exception);
        }
        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw invalid(name);
        }
        return require(parsed, name);
    }

    public static UUID require(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.version() != 4 || value.variant() != 2) {
            throw invalid(name);
        }
        return value;
    }

    private static IllegalArgumentException invalid(String name) {
        return new IllegalArgumentException(name + " must be a UUID version 4");
    }

    private static IllegalArgumentException invalid(String name, RuntimeException cause) {
        return new IllegalArgumentException(name + " must be a UUID version 4", cause);
    }
}
