package ca.deliyannides.dirtmcp.paper.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class UuidV4Test {
    private static final UUID VALID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

    @Test
    void parsesCanonicalVersionFourIdsCaseInsensitively() {
        assertEquals(VALID, UuidV4.parseCanonical(VALID.toString(), "id"));
        assertEquals(VALID, UuidV4.parseCanonical(VALID.toString().toUpperCase(Locale.ROOT), "id"));
    }

    @Test
    void rejectsMalformedNonCanonicalWrongVersionAndWrongVariantIds() {
        for (String invalid :
                new String[] {
                    null,
                    "not-a-uuid",
                    "1-1-4000-8000-1",
                    "123e4567-e89b-12d3-a456-426614174000",
                    "123e4567-e89b-42d3-7456-426614174000"
                }) {
            IllegalArgumentException failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> UuidV4.parseCanonical(invalid, "testId"));
            assertEquals("testId must be a UUID version 4", failure.getMessage());
        }
    }

    @Test
    void validatesUuidObjects() {
        assertEquals(VALID, UuidV4.require(VALID, "id"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UuidV4.require(
                                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), "id"));
        assertThrows(NullPointerException.class, () -> UuidV4.require(null, "id"));
    }
}
