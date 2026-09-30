package com.github.starlight.world;

import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/** Client chunk lookup, kept out of WorldLight so a dedicated server never resolves client classes. */
final class ClientChunks {

    private ClientChunks() {}

    static Chunk getLoaded(final World world, final int chunkX, final int chunkZ) {
        return ((ChunkProviderClient)world.getChunkProvider()).getLoadedChunk(chunkX, chunkZ);
    }
}