package ca.deliyannides.dirtmcp.paper.validation;

import java.util.regex.Pattern;

/** Shared wire-credential format used by Dirt's authenticated loopback services. */
public final class ServiceToken {
    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    private ServiceToken() {}

    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }
}
