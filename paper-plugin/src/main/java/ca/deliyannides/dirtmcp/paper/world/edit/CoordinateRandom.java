package ca.deliyannides.dirtmcp.paper.world.edit;

final class CoordinateRandom {
    private static final double UNIT_DOUBLE = 0x1.0p-53;

    private final long seed;

    CoordinateRandom(int seed) {
        this.seed = Integer.toUnsignedLong(seed);
    }

    double at(int x, int y, int z) {
        long value = mix(this.seed ^ 0x9e3779b97f4a7c15L);
        value = mix(value ^ Integer.toUnsignedLong(x));
        value = mix(value ^ Integer.toUnsignedLong(y));
        value = mix(value ^ Integer.toUnsignedLong(z));
        return (value >>> 11) * UNIT_DOUBLE;
    }

    private static long mix(long value) {
        long mixed = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }
}
