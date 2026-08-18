package ca.deliyannides.dirtmcp.paper.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class BearerAuthentication {
    private static final String SCHEME = "Bearer";

    private final byte[] expectedToken;

    BearerAuthentication(String token, int minimumTokenBytes) {
        if (minimumTokenBytes < 1) {
            throw new IllegalArgumentException("Minimum token bytes must be positive");
        }
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        if (tokenBytes.length < minimumTokenBytes) {
            throw new IllegalArgumentException(
                    "DIRT_MCP_BRIDGE_TOKEN must contain at least "
                            + minimumTokenBytes
                            + " bytes");
        }
        this.expectedToken = tokenBytes.clone();
    }

    boolean accepts(String authorizationHeader) {
        if (authorizationHeader == null) {
            return false;
        }

        int separator = authorizationHeader.indexOf(' ');
        if (separator < 0 || !authorizationHeader.substring(0, separator).equalsIgnoreCase(SCHEME)) {
            return false;
        }

        byte[] suppliedToken = authorizationHeader.substring(separator + 1).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(this.expectedToken, suppliedToken);
    }
}
