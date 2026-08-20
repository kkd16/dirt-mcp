package ca.deliyannides.dirtmcp.paper.world.edit;

record ChunkPosition(int x, int z) {
    static ChunkPosition containing(int blockX, int blockZ) {
        return new ChunkPosition(blockX >> 4, blockZ >> 4);
    }
}
