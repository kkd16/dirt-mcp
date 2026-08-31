package ca.deliyannides.dirtmcp.paper.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

final class BearerAuthenticator {
    private static final String SCHEME = "Bearer";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final byte[] expectedToken;

    BearerAuthenticator(String token) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            throw new IllegalArgumentException(
                    "DIRT_BRIDGE_TOKEN must contain exactly 64 lowercase hexadecimal "
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
