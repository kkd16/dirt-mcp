package ca.deliyannides.dirtmcp.paper.world.model;

/** Compact origin-relative geometry shared by block inspection and editing. */
public final class BlockStructure {
    private BlockStructure() {}

    public record Placement(int paletteIndex, int x, int y, int z) {}

    public record Run(int paletteIndex, int x, int y, int z, int toX, int toY, int toZ) {}
}
