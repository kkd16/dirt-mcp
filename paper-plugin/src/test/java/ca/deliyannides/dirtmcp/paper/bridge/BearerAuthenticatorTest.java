package ca.deliyannides.dirtmcp.paper.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BearerAuthenticatorTest {
    @Test
    void acceptsOnlyTheConfiguredBearerToken() {
        BearerAuthenticator authenticator =
                new BearerAuthenticator("test-token-with-at-least-thirty-two-bytes");

        assertTrue(authenticator.accepts("Bearer test-token-with-at-least-thirty-two-bytes"));
        assertTrue(authenticator.accepts("bearer test-token-with-at-least-thirty-two-bytes"));
        assertFalse(authenticator.accepts(null));
        assertFalse(authenticator.accepts("test-token-with-at-least-thirty-two-bytes"));
        assertFalse(authenticator.accepts("Basic test-token-with-at-least-thirty-two-bytes"));
        assertFalse(authenticator.accepts("Bearer wrong"));
        assertFalse(authenticator.accepts("Bearer  test-token-with-at-least-thirty-two-bytes"));
    }

    @Test
    void requiresAtLeastThirtyTwoUtf8Bytes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new BearerAuthenticator("1234567890123456789012345678901"));
        assertTrue(new BearerAuthenticator("éééééééééééééééé").accepts("Bearer éééééééééééééééé"));
    }
}
