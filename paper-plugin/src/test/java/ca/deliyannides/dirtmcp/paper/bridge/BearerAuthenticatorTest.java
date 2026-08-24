package ca.deliyannides.dirtmcp.paper.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BearerAuthenticatorTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void acceptsOnlyTheConfiguredBearerToken() {
        BearerAuthenticator authenticator = new BearerAuthenticator(TOKEN);

        assertTrue(authenticator.accepts("Bearer " + TOKEN));
        assertTrue(authenticator.accepts("bearer " + TOKEN));
        assertFalse(authenticator.accepts(null));
        assertFalse(authenticator.accepts(TOKEN));
        assertFalse(authenticator.accepts("Basic " + TOKEN));
        assertFalse(authenticator.accepts("Bearer wrong"));
        assertFalse(authenticator.accepts("Bearer  " + TOKEN));
    }

    @Test
    void requiresExactlySixtyFourLowercaseHexadecimalCharacters() {
        for (String token :
                java.util.List.of(
                        "a".repeat(63),
                        "a".repeat(65),
                        "A".repeat(64),
                        "a".repeat(63) + "g",
                        "🔒".repeat(16),
                        TOKEN + "\n")) {
            assertThrows(IllegalArgumentException.class, () -> new BearerAuthenticator(token));
        }
        assertThrows(IllegalArgumentException.class, () -> new BearerAuthenticator(null));
    }
}
