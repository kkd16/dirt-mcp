package ca.deliyannides.dirtmcp.paper.world.model;

/** Finite three-dimensional vector with negative zero canonicalized for stable wire output. */
public record Vector3(double x, double y, double z) {
    public Vector3 {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        x = canonicalZero(x);
        y = canonicalZero(y);
        z = canonicalZero(z);
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static double canonicalZero(double value) {
        return value == 0 ? 0 : value;
    }
}
