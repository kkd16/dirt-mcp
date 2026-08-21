package ca.deliyannides.dirtmcp.paper.world.model;

/** Shared bounds checks for exact coordinates that Paper will convert to block positions. */
public final class BlockCoordinates {
    private BlockCoordinates() {}

    public static boolean contains(double value) {
        return Double.isFinite(value)
                && value >= Integer.MIN_VALUE
                && value < (double) Integer.MAX_VALUE + 1.0;
    }
}
