package ca.deliyannides.dirtmcp.paper.world.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CoordinateRandomTest {
    @Test
    void isDeterministicAndCoordinateSeeded() {
        CoordinateRandom first = new CoordinateRandom(42);
        CoordinateRandom same = new CoordinateRandom(42);
        CoordinateRandom otherSeed = new CoordinateRandom(43);

        assertEquals(first.at(7, -3, 19), same.at(7, -3, 19));
        assertNotEquals(first.at(7, -3, 19), first.at(8, -3, 19));
        assertNotEquals(first.at(7, -3, 19), otherSeed.at(7, -3, 19));
        assertTrue(first.at(7, -3, 19) >= 0.0);
        assertTrue(first.at(7, -3, 19) < 1.0);
    }
}
