package ca.deliyannides.dirtmcp.paper.world.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class SpatialValuesTest {
    @Test
    void rejectsNonFiniteSpatialValues() {
        assertThrows(IllegalArgumentException.class, () -> new ExactPosition(Double.NaN, 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> new Rotation(0, Double.POSITIVE_INFINITY));
        assertThrows(
                IllegalArgumentException.class, () -> new Vector3(0, 0, Double.NEGATIVE_INFINITY));
    }

    @Test
    void canonicalizesNegativeZeroInVectors() {
        Vector3 vector = new Vector3(-0.0, -0.0, -0.0);

        assertEquals(0L, Double.doubleToRawLongBits(vector.x()));
        assertEquals(0L, Double.doubleToRawLongBits(vector.y()));
        assertEquals(0L, Double.doubleToRawLongBits(vector.z()));
    }
}
