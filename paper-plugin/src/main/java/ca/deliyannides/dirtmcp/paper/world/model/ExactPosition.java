package ca.deliyannides.dirtmcp.paper.world.model;

/** Finite world-space coordinates without block rounding. */
public record ExactPosition(double x, double y, double z) {
    public ExactPosition {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
