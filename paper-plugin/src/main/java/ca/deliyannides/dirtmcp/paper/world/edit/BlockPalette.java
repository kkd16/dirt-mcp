package ca.deliyannides.dirtmcp.paper.world.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BlockPalette<T> {
    private static final double UNIT_DOUBLE = 0x1.0p-53;

    private final List<T> values;
    private final int[] cumulativeWeights;
    private final int totalWeight;
    private final long seed;

    BlockPalette(List<WeightedValue<T>> entries, int seed) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Palette must not be empty");
        }
        this.values = new ArrayList<>(entries.size());
        this.cumulativeWeights = new int[entries.size()];
        int cumulative = 0;
        for (int index = 0; index < entries.size(); index++) {
            WeightedValue<T> entry = Objects.requireNonNull(entries.get(index), "entry");
            if (entry.weight() < 1) {
                throw new IllegalArgumentException("Palette weights must be positive");
            }
            cumulative = Math.addExact(cumulative, entry.weight());
            this.values.add(Objects.requireNonNull(entry.value(), "value"));
            this.cumulativeWeights[index] = cumulative;
        }
        this.totalWeight = cumulative;
        this.seed = Integer.toUnsignedLong(seed);
    }

    T at(int x, int y, int z) {
        double random = randomAt(x, y, z);
        double choice = random * this.totalWeight;
        for (int index = 0; index < this.cumulativeWeights.length; index++) {
            if (choice < this.cumulativeWeights[index]) {
                return this.values.get(index);
            }
        }
        return this.values.getLast();
    }

    double randomAt(int x, int y, int z) {
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

    record WeightedValue<T>(T value, int weight) {}
}
