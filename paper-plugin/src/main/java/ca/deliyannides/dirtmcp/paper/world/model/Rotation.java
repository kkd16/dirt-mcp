package ca.deliyannides.dirtmcp.paper.world.model;

/** Finite Paper yaw and pitch values in degrees. */
public record Rotation(double yaw, double pitch) {
    public Rotation {
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
