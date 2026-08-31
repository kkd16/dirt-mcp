package ca.deliyannides.dirtmcp.paper.bridge;

import ca.deliyannides.dirtmcp.paper.validation.ServiceToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class BearerAuthenticator {
    private static final String SCHEME = "Bearer";
    private final byte[] expectedToken;

    BearerAuthenticator(String token) {
        if (!ServiceToken.isValid(token)) {
            throw new IllegalArgumentException(
                    "DIRT_BRIDGE_TOKEN_FILE contents must contain exactly 64 lowercase hexadecimal "
                            + "characters");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
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
