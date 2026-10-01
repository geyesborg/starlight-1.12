package com.github.starlight.world;

import com.github.starlight.Starlight;
import com.github.starlight.light.LightChunk;
import com.github.starlight.light.LightWorld;
import com.github.starlight.light.SWMRNibbleArray;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Dev check (-Dstarlight.verify=true): every 200 ticks a random lit chunk is compared with exact
 * light computed from the real blocks
 */
public final class LightVerifier {

    public static final boolean ENABLED = Boolean.getBoolean("starlight.verify");
    // -Dstarlight.verifyEdits=true: random block edits around the chunk first (light updates
    // through World.setBlockState)
    private static final boolean EDITS = Boolean.getBoolean("starlight.verifyEdits");

    private static final int MIN_Y = LightWorld.MIN_LIGHT_SECTION << 4, MAX_Y = (LightWorld.MAX_LIGHT_SECTION << 4) | 15;
    private static final int HEIGHT = MAX_Y - MIN_Y + 1, SIZE = 5 * 16;

    private static int ticks;
    private static long checked, clean;

    private LightVerifier() {}

    // -Dstarlight.staleEdgeTest=true: once, 300 ticks in, change light at a chunk border while the
    // chunk on the other side is unloaded, reload it from disk (saved light) and verify both
    public static final boolean STALE_EDGE_TEST = Boolean.getBoolean("starlight.staleEdgeTest");

    // -Dstarlight.verifyBurst=N: once, 400 ticks in, verify N random lit chunks (5x5 lit) at once
    public static final int BURST = Integer.getInteger("starlight.verifyBurst", 0);

    public static void tick(final WorldServer world, final WorldLight light) {
        ++ticks;
        if (BURST > 0 && ticks == 400 && world.provider.getDimension() == 0) {
            light.propagateChanges();
            final List<Chunk> c = candidates(world, light);
            Collections.shuffle(c, new Random(42));
            int bad = 0, n = 0;
            for (final Chunk chunk : c.subList(0, Math.min(BURST, c.size()))) {
                bad += verify(world, light, chunk) ? 0 : 1;
                ++n;
            }
            Starlight.LOGGER.info("[Starlight verify burst] {} of {} chunks with mismatches ({} candidates)", bad, n, c.size());
        }
        if (STALE_EDGE_TEST && ticks == 300 && world.provider.getDimension() == 0) {
            staleEdgeTest(world, light, 6000, 6000, false);
            staleEdgeTest(world, light, 6100, 6000, true);
        }
        if (!ENABLED || ticks % 200 != 0) {
            return;
        }
        light.propagateChanges();
        final List<Chunk> candidates = candidates(world, light);
        if (candidates.isEmpty()) {
            return;
        }
        final Chunk center = candidates.get(world.rand.nextInt(candidates.size()));
        if (EDITS) {
            edit(world, center);
            light.propagateChanges();
        }
        verify(world, light, center);
    }

    /**
     * One of two neighbouring chunks is saved and unloaded, light changes along their border, then
     * it is reloaded with its saved light: a skipped edge check would leave stale light
     */
    private static void staleEdgeTest(final WorldServer world, final WorldLight light, final int cx, final int cz, final boolean unloadNeighbour) {
        final ChunkProviderServer provider = (ChunkProviderServer)world.getChunkProvider();
        for (int dx = -3; dx <= 4; ++dx) {
            for (int dz = -3; dz <= 3; ++dz) {
                provider.provideChunk(cx + dx, cz + dz);
            }
        }
        light.propagateChanges();
        final Chunk a = provider.getLoadedChunk(cx, cz), n = provider.getLoadedChunk(cx + 1, cz);
        final Chunk unloaded = unloadNeighbour ? n : a, edited = unloadNeighbour ? a : n;
        // save + unload one chunk now (the provider unloads queued chunks in its tick)
        provider.queueUnload(unloaded);
        provider.tick();
        final boolean gone = provider.loadedChunks.get(ChunkPos.asLong(unloaded.x, unloaded.z)) == null;
        // light changes in the edited chunk's border column facing the unloaded one
        final int bx = unloadNeighbour ? (cx << 4) | 15 : (cx + 1) << 4;
        int placed = 0;
        for (int z = cz << 4; z < (cz << 4) + 16; z += 3) {
            for (int y = 20; y < 120; y += 7) {
                final BlockPos p = new BlockPos(bx, y, z);
                world.setBlockState(p, (y / 7 + z) % 2 == 0 ? Blocks.GLOWSTONE.getDefaultState()
                        : Blocks.AIR.getDefaultState(), 2 | 16); // 16: no observer updates (they would load the unloaded chunk)
                ++placed;
            }
        }
        light.propagateChanges();
        // reload from disk: saved light, then (if detected) the edge check
        final Chunk back = provider.provideChunk(unloaded.x, unloaded.z);
        light.propagateChanges();
        final boolean fromSave = ((WorldLight.StarlightChunkState)back).starlight$isLightFromSave();
        Starlight.LOGGER.info("[Starlight stale-edge test] unloaded {} ({},{}) gone={} reloaded from saved light={}, {} border changes in {} ({},{})",
                unloadNeighbour ? "N" : "A", unloaded.x, unloaded.z, gone, fromSave, placed, unloadNeighbour ? "A" : "N", edited.x, edited.z);
        verify(world, light, back);
        verify(world, light, provider.getLoadedChunk(edited.x, edited.z));
    }

    private static List<Chunk> candidates(final WorldServer world, final WorldLight light) {
        final List<Chunk> candidates = new ArrayList<>();
        outer:
        for (final Chunk c : ((ChunkProviderServer)world.getChunkProvider()).loadedChunks.values()) {
            for (int dx = -2; dx <= 2; ++dx) {
                for (int dz = -2; dz <= 2; ++dz) {
                    final LightChunk n = light.getChunkForLighting(c.x + dx, c.z + dz);
                    if (n == null || !n.starlight$isLightReady()) {
                        continue outer;
                    }
                }
            }
            candidates.add(c);
        }
        return candidates;
    }

    private static boolean verify(final WorldServer world, final WorldLight light, final Chunk center) {
        final long start = System.nanoTime();
        final int x0 = (center.x - 2) << 4, z0 = (center.z - 2) << 4;
        final byte[] opacity = new byte[SIZE * SIZE * HEIGHT];
        final byte[] emission = new byte[SIZE * SIZE * HEIGHT];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < SIZE; ++x) {
            for (int z = 0; z < SIZE; ++z) {
                final Chunk c = (Chunk)light.getChunkForLighting((x0 + x) >> 4, (z0 + z) >> 4);
                for (int y = 0; y <= 255; ++y) {
                    pos.setPos(x0 + x, y, z0 + z);
                    final IBlockState state = c.getBlockState(pos);
                    final int i = index(x, y, z);
                    opacity[i] = (byte)Math.min(15, state.getLightOpacity(world, pos));
                    emission[i] = (byte)state.getLightValue(world, pos);
                }
            }
        }
        final byte[] block = propagate(opacity, emission, false);
        final byte[] sky = light.hasSkyLight() ? propagate(opacity, emission, true) : new byte[opacity.length];

        int blockBad = 0, skyBad = 0, skyBadNull = 0;
        final StringBuilder sample = new StringBuilder();
        final SWMRNibbleArray[] skyNibbles = ((LightChunk)center).starlight$getSkyNibbles();
        for (int lx = 0; lx < 16; ++lx) {
            for (int lz = 0; lz < 16; ++lz) {
                final int wx = (center.x << 4) | lx, wz = (center.z << 4) | lz;
                for (int y = MIN_Y; y <= MAX_Y; ++y) {
                    final int i = index(wx - x0, y, wz - z0);
                    final int b = light.getBlockLight(center, wx, y, wz);
                    final int s = light.getSkyLight(center, wx, y, wz);
                    if (b != block[i] && blockBad++ < 3) {
                        sample.append(String.format(" block(%d,%d,%d)=%d want %d", wx, y, wz, b, block[i]));
                    }
                    if (s != sky[i]) {
                        final SWMRNibbleArray n = skyNibbles[(y >> 4) + 1];
                        if (n == null || n.isNullNibbleVisible()) {
                            ++skyBadNull;
                        } else if (skyBad++ < 3) {
                            sample.append(String.format(" sky(%d,%d,%d)=%d want %d", wx, y, wz, s, sky[i]));
                        }
                    }
                }
            }
        }
        ++checked;
        if (blockBad == 0 && skyBad == 0 && skyBadNull == 0) {
            ++clean;
        }
        Starlight.LOGGER.info("[Starlight verify] chunk {},{}: {} block / {} sky / {} sky-in-empty-sections mismatches ({} of {} chunks clean, {} ms){}",
                center.x, center.z, blockBad, skyBad, skyBadNull, clean, checked,
                String.format("%.1f", (System.nanoTime() - start) / 1e6), sample);
        return blockBad == 0 && skyBad == 0 && skyBadNull == 0;
    }

    private static final IBlockState[] EDIT_STATES = {
            Blocks.AIR.getDefaultState(), Blocks.STONE.getDefaultState(),
            Blocks.GLASS.getDefaultState(), Blocks.GLOWSTONE.getDefaultState(),
            Blocks.TORCH.getDefaultState(), Blocks.WATER.getDefaultState(),
            Blocks.LEAVES.getDefaultState(), Blocks.ICE.getDefaultState(),
    };

    /** 150 random block changes over the 3x3 chunks around {@code center}, y 30..130 (no neighbour updates, no drops). */
    private static void edit(final WorldServer world, final Chunk center) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 150; ++i) {
            pos.setPos(((center.x - 1) << 4) + world.rand.nextInt(48), 30 + world.rand.nextInt(100), ((center.z - 1) << 4) + world.rand.nextInt(48));
            world.setBlockState(pos, EDIT_STATES[world.rand.nextInt(EDIT_STATES.length)], 2 | 16);
        }
    }

    private static int index(final int x, final int y, final int z) {
        return (x * SIZE + z) * HEIGHT + (y - MIN_Y);
    }

    /** Exact max-propagation: light = max(source, neighbour - max(1, opacity)) (bucket queue). */
    private static byte[] propagate(final byte[] opacity, final byte[] emission, final boolean sky) {
        final byte[] level = new byte[opacity.length];
        final IntArrayList[] buckets = new IntArrayList[16];
        Arrays.setAll(buckets, i -> new IntArrayList());
        for (int x = 0; x < SIZE; ++x) {
            for (int z = 0; z < SIZE; ++z) {
                if (sky) {
                    for (int y = MAX_Y; y >= MIN_Y && opacity[index(x, y, z)] == 0; --y) {
                        level[index(x, y, z)] = 15;
                        buckets[15].add(index(x, y, z));
                    }
                } else {
                    for (int y = 0; y <= 255; ++y) {
                        final int e = emission[index(x, y, z)];
                        if (e > 0) {
                            level[index(x, y, z)] = (byte)e;
                            buckets[e].add(index(x, y, z));
                        }
                    }
                }
            }
        }
        for (int l = 15; l >= 2; --l) {
            final IntArrayList bucket = buckets[l];
            for (int k = 0; k < bucket.size(); ++k) {
                final int i = bucket.getInt(k);
                if (level[i] != l) {
                    continue;
                }
                final int y = i % HEIGHT + MIN_Y, z = (i / HEIGHT) % SIZE, x = i / (HEIGHT * SIZE);
                for (int d = 0; d < 6; ++d) {
                    final int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    final int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    final int nz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
                    if (nx < 0 || nx >= SIZE || nz < 0 || nz >= SIZE || ny < MIN_Y || ny > MAX_Y) {
                        continue;
                    }
                    final int n = index(nx, ny, nz);
                    final int t = l - Math.max(1, opacity[n]);
                    if (t > level[n]) {
                        level[n] = (byte)t;
                        buckets[t].add(n);
                    }
                }
            }
        }
        return level;
    }
}
