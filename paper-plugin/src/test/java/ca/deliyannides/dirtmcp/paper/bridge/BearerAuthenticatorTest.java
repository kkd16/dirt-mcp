package ca.deliyannides.dirtmcp.paper.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BearerAuthenticatorTest {
    @Test
    void acceptsOnlyTheConfiguredBearerToken() {
        BearerAuthenticator authenticator = new BearerAuthenticator("secret-token", 12);

        assertTrue(authenticator.accepts("Bearer secret-token"));
        assertTrue(authenticator.accepts("bearer secret-token"));
        assertFalse(authenticator.accepts(null));
        assertFalse(authenticator.accepts("secret-token"));
        assertFalse(authenticator.accepts("Basic secret-token"));
        assertFalse(authenticator.accepts("Bearer wrong"));
        assertFalse(authenticator.accepts("Bearer  secret-token"));
    }

    @Test
    void validatesMinimumTokenBytes() {
        assertThrows(IllegalArgumentException.class, () -> new BearerAuthenticator("token", 0));
        assertThrows(IllegalArgumentException.class, () -> new BearerAuthenticator("é", 3));
        assertTrue(new BearerAuthenticator("é", 2).accepts("Bearer é"));
    }
}
