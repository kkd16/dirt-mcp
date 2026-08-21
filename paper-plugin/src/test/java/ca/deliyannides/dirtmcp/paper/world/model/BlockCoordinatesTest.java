package ca.deliyannides.dirtmcp.paper.world.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BlockCoordinatesTest {
    @Test
    void recognizesTheFullSignedBlockCoordinateDomain() {
        assertTrue(BlockCoordinates.contains(Integer.MIN_VALUE));
        assertTrue(BlockCoordinates.contains(Math.nextDown((double) Integer.MAX_VALUE + 1.0)));
        assertFalse(BlockCoordinates.contains(Math.nextDown((double) Integer.MIN_VALUE)));
        assertFalse(BlockCoordinates.contains((double) Integer.MAX_VALUE + 1.0));
        assertFalse(BlockCoordinates.contains(Double.NaN));
    }
}
