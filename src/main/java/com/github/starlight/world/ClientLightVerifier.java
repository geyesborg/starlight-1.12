package com.github.starlight.world;

import com.github.starlight.Starlight;
import com.github.starlight.light.LightChunk;
import com.github.starlight.light.LightWorld;
import com.github.starlight.light.SWMRNibbleArray;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Dev check (-Dstarlight.verifyClient=true): every 10 s a client chunk is compared with the
 * integrated server's
 */
public final class ClientLightVerifier {

    public static final boolean ENABLED = Boolean.getBoolean("starlight.verifyClient");

    private static long nextCheck, checked, clean;

    private ClientLightVerifier() {}

    public static void frame(final Minecraft mc, final WorldLight clientLight) {
        final long now = System.nanoTime();
        if (now < nextCheck || mc.world == null || mc.player == null) {
            return;
        }
        nextCheck = now + 10_000_000_000L;
        if (mc.getIntegratedServer() == null) {
            verifyMultiplayer(mc, clientLight);
            return;
        }
        final WorldServer serverWorld = mc.getIntegratedServer().getWorld(mc.world.provider.getDimension());
        final WorldLight serverLight = serverWorld == null ? null : ((StarlightWorld)serverWorld).starlight$getLight();
        if (serverLight == null || serverLight.hasPendingChanges() || clientLight.hasPendingChanges()) {
            return;
        }
        final List<Chunk> candidates = new ArrayList<>();
        final ChunkProviderClient clientChunks = (ChunkProviderClient)mc.world.getChunkProvider();
        for (final Chunk s : ((ChunkProviderServer)serverWorld.getChunkProvider()).loadedChunks.values()) {
            // the 3x3 neighbourhood lit on both sides: nothing further away can reach the chunk
            boolean ready = true;
            for (int dx = -1; dx <= 1 && ready; ++dx) {
                for (int dz = -1; dz <= 1 && ready; ++dz) {
                    final LightChunk cn = clientLight.getChunkForLighting(s.x + dx, s.z + dz);
                    final LightChunk sn = serverLight.getChunkForLighting(s.x + dx, s.z + dz);
                    ready = cn != null && sn != null && cn.starlight$isLightReady() && sn.starlight$isLightReady();
                }
            }
            if (ready) {
                candidates.add(clientChunks.getLoadedChunk(s.x, s.z));
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        final Chunk client = candidates.get(mc.world.rand.nextInt(candidates.size()));
        final Chunk server = ((ChunkProviderServer)serverWorld.getChunkProvider()).loadedChunks.get(
                ChunkPos.asLong(client.x, client.z));

        int lightDiff = 0, arrayDiff = 0;
        final StringBuilder sample = new StringBuilder();
        for (int lx = 0; lx < 16; ++lx) {
            for (int lz = 0; lz < 16; ++lz) {
                final int x = (client.x << 4) | lx, z = (client.z << 4) | lz;
                for (int y = LightWorld.MIN_LIGHT_SECTION << 4; y <= ((LightWorld.MAX_LIGHT_SECTION << 4) | 15); ++y) {
                    final int cb = clientLight.getBlockLight(client, x, y, z), sb = serverLight.getBlockLight(server, x, y, z);
                    final int cs = clientLight.getSkyLight(client, x, y, z), ss = serverLight.getSkyLight(server, x, y, z);
                    if ((cb != sb || cs != ss) && lightDiff++ < 3) {
                        sample.append(String.format(" (%d,%d,%d) client %d/%d server %d/%d", x, y, z, cb, cs, sb, ss));
                    }
                    if (y >= 0 && y <= 255) {
                        final ExtendedBlockStorage ebs = client.getBlockStorageArray()[y >> 4];
                        if (ebs != null && (ebs.getBlockLight(lx, y & 15, lz) != cb
                                || (ebs.getSkyLight() != null && ebs.getSkyLight(lx, y & 15, lz) != cs && !skyNull(client, y)))) {
                            ++arrayDiff;
                        }
                    }
                }
            }
        }
        ++checked;
        if (lightDiff == 0 && arrayDiff == 0) {
            ++clean;
        }
        Starlight.LOGGER.info("[Starlight verify client] chunk {},{}: {} light differences vs server, {} vanilla array differences ({} of {} clean){}",
                client.x, client.z, lightDiff, arrayDiff, clean, checked, sample);
    }

    // Multiplayer (no server to compare with): a client chunk near the player whose 5x5 neighbourhood
    // is lit, against exact light computed from the client's own blocks
    private static void verifyMultiplayer(final Minecraft mc, final WorldLight clientLight) {
        if (clientLight.hasPendingChanges()) {
            return;
        }
        final int px = mc.player.chunkCoordX, pz = mc.player.chunkCoordZ, r = mc.gameSettings.renderDistanceChunks;
        final List<Chunk> candidates = new ArrayList<>();
        for (int cx = px - r; cx <= px + r; ++cx) {
            outer:
            for (int cz = pz - r; cz <= pz + r; ++cz) {
                for (int dx = -2; dx <= 2; ++dx) {
                    for (int dz = -2; dz <= 2; ++dz) {
                        final LightChunk n = clientLight.getChunkForLighting(cx + dx, cz + dz);
                        if (n == null || !n.starlight$isLightReady()) {
                            continue outer;
                        }
                    }
                }
                candidates.add((Chunk)clientLight.getChunkForLighting(cx, cz));
            }
        }
        if (!candidates.isEmpty()) {
            LightVerifier.verify(mc.world, clientLight, candidates.get(mc.world.rand.nextInt(candidates.size())));
        }
    }

    // A section without sky data mirrors zeros into its vanilla array while reads extrude from above
    private static boolean skyNull(final Chunk chunk, final int y) {
        final SWMRNibbleArray n = ((LightChunk)chunk).starlight$getSkyNibbles()[(y >> 4) + 1];
        return n == null || n.isNullNibbleVisible();
    }
}
