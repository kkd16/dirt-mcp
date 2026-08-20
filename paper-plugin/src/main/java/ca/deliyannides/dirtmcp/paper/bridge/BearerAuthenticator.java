package ca.deliyannides.dirtmcp.paper.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class BearerAuthenticator {
    private static final String SCHEME = "Bearer";
    private static final int MINIMUM_TOKEN_BYTES = 32;

    private final byte[] expectedToken;

    BearerAuthenticator(String token) {
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        if (tokenBytes.length < MINIMUM_TOKEN_BYTES) {
            throw new IllegalArgumentException(
                    "DIRT_MCP_BRIDGE_TOKEN must contain at least "
                            + MINIMUM_TOKEN_BYTES
                            + " bytes");
        }
        this.expectedToken = tokenBytes.clone();
    }

    boolean accepts(String authorizationHeader) {
        if (authorizationHeader == null) {
            return false;
        }

        int separator = authorizationHeader.indexOf(' ');
        if (separator < 0
                || !authorizationHeader.substring(0, separator).equalsIgnoreCase(SCHEME)) {
            return false;
        }

        byte[] suppliedToken =
                authorizationHeader.substring(separator + 1).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(this.expectedToken, suppliedToken);
    }
}
