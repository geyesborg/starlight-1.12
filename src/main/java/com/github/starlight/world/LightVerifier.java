package com.github.starlight.world;

import com.github.starlight.Starlight;
import com.github.starlight.light.LightChunk;
import com.github.starlight.light.LightWorld;
import com.github.starlight.light.SWMRNibbleArray;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Dev check ({@code -Dstarlight.verify=true}): every 200 ticks, a random lit chunk whose 5x5
 * neighbourhood is loaded and lit is compared, position by position, with exact light computed
 * from the real blocks over that area (light travels at most 15 blocks, so the 2-chunk margin
 * holds everything that can reach the centre chunk). Sky mismatches are split into positions
 * in sections with light data and in sections without (read by extrusion).
 */
public final class LightVerifier {

    public static final boolean ENABLED = Boolean.getBoolean("starlight.verify");
    // -Dstarlight.verifyEdits=true: random block edits around the chunk first (light updates through World.setBlockState)
    private static final boolean EDITS = Boolean.getBoolean("starlight.verifyEdits");

    private static final int MIN_Y = LightWorld.MIN_LIGHT_SECTION << 4, MAX_Y = (LightWorld.MAX_LIGHT_SECTION << 4) | 15;
    private static final int HEIGHT = MAX_Y - MIN_Y + 1, SIZE = 5 * 16;

    private static int ticks;
    private static long checked, clean;

    private LightVerifier() {}

    public static void tick(final WorldServer world, final WorldLight light) {
        if (++ticks % 200 != 0) {
            return;
        }
        light.propagateChanges();
        final List<Chunk> candidates = new ArrayList<>();
        final ChunkProviderServer provider = (ChunkProviderServer)world.getChunkProvider();
        outer:
        for (final Chunk c : provider.loadedChunks.values()) {
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

    private static void verify(final WorldServer world, final WorldLight light, final Chunk center) {
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
    }

    private static final IBlockState[] EDIT_STATES = {
            net.minecraft.init.Blocks.AIR.getDefaultState(), net.minecraft.init.Blocks.STONE.getDefaultState(),
            net.minecraft.init.Blocks.GLASS.getDefaultState(), net.minecraft.init.Blocks.GLOWSTONE.getDefaultState(),
            net.minecraft.init.Blocks.TORCH.getDefaultState(), net.minecraft.init.Blocks.WATER.getDefaultState(),
            net.minecraft.init.Blocks.LEAVES.getDefaultState(), net.minecraft.init.Blocks.ICE.getDefaultState(),
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
