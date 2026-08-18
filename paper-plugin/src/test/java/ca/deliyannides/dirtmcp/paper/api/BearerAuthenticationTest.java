package ca.deliyannides.dirtmcp.paper.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BearerAuthenticationTest {
    private static final String TOKEN = "test-token-with-at-least-thirty-two-bytes";

    @Test
    void acceptsOnlyTheExactBearerToken() {
        BearerAuthentication authentication = new BearerAuthentication(TOKEN, 32);

        assertTrue(authentication.accepts("Bearer " + TOKEN));
        assertTrue(authentication.accepts("bearer " + TOKEN));
        assertFalse(authentication.accepts(null));
        assertFalse(authentication.accepts(TOKEN));
        assertFalse(authentication.accepts("Basic " + TOKEN));
        assertFalse(authentication.accepts("Bearer wrong-token-with-at-least-thirty-two-bytes"));
    }

    @Test
    void rejectsShortTokens() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BearerAuthentication("too-short", 32));
    }

    @Test
    void usesTheConfiguredMinimumTokenLength() {
        BearerAuthentication authentication = new BearerAuthentication("nine-byte", 9);

        assertTrue(authentication.accepts("Bearer nine-byte"));
    }
}
