package com.github.starlight.world;

import com.github.starlight.light.LightChunk;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

/** Client-only lookups, kept out of WorldLight so a dedicated server never resolves client classes. */
final class ClientChunks {

    private ClientChunks() {}

    static Chunk getLoaded(final World world, final int chunkX, final int chunkZ) {
        return ((ChunkProviderClient)world.getChunkProvider()).getLoadedChunk(chunkX, chunkZ);
    }

    /**
     * Singleplayer: the integrated server's copy of this chunk if Starlight has lit it there, else
     * null. Its visible light may be read from this thread (single-writer/multi-reader arrays).
     */
    static Chunk litServerChunk(final World clientWorld, final int chunkX, final int chunkZ) {
        final IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server == null) {
            return null;
        }
        final WorldServer world = server.getWorld(clientWorld.provider.getDimension());
        if (world == null || ((StarlightWorld)world).starlight$getLight() == null) {
            return null;
        }
        final Chunk chunk = ((ChunkProviderServer)world.getChunkProvider()).loadedChunks.get(ChunkPos.asLong(chunkX, chunkZ));
        return chunk != null && ((LightChunk)chunk).starlight$isLightReady() ? chunk : null;
    }
}