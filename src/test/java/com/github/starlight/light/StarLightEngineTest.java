package com.github.starlight.light;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static com.github.starlight.light.SyntheticWorld.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ported engines against an exact light fixpoint on synthetic 1.12 worlds: initial lighting
 * chunk by chunk (edge checks between neighbours), relighting, and block edits that change
 * opacity, emission and section emptiness.
 */
class StarLightEngineTest {

    @Test
    void nibbleArrayStatesAndVisibility() {
        final SWMRNibbleArray n = new SWMRNibbleArray();
        assertTrue(n.isUninitialisedVisible());
        n.set(3, 4, 5, 11);
        assertEquals(11, n.getUpdating(3, 4, 5));
        assertEquals(0, n.getVisible(3, 4, 5), "not visible before updateVisible");
        assertTrue(n.updateVisible());
        assertEquals(11, n.getVisible(3, 4, 5));
        assertTrue(n.isInitialisedVisible());

        final SWMRNibbleArray below = new SWMRNibbleArray(null, true);
        below.setNonNull();
        below.extrudeLower(n);
        below.updateVisible();
        assertEquals(n.getVisible(3, 0, 5), below.getVisible(3, 15, 5), "extrude copies the bottom row into every row");

        final byte[] vanilla = new byte[SWMRNibbleArray.ARRAY_SIZE];
        n.copyVisibleInto(vanilla);
        final int idx = 3 | (5 << 4) | (4 << 8);
        assertEquals(11, (vanilla[idx >>> 1] >>> ((idx & 1) << 2)) & 0xF, "same nibble layout as NibbleArray");
    }

    @Test
    void torchOnFlatGround() {
        final SyntheticWorld w = new SyntheticWorld();
        for (int cx = -1; cx <= 1; ++cx) {
            for (int cz = -1; cz <= 1; ++cz) {
                w.addChunk(cx, cz);
            }
        }
        for (int x = -16; x < 32; ++x) {
            for (int z = -16; z < 32; ++z) {
                for (int y = 0; y < 64; ++y) {
                    w.setBlockRaw(x, y, z, STONE);
                }
            }
        }
        w.setBlockRaw(8, 64, 8, TORCH);
        for (final SyntheticWorld.Chunk c : w.chunks.values()) {
            w.lightChunk(c);
        }
        assertEquals(14, w.getBlockLight(8, 64, 8));
        assertEquals(13, w.getBlockLight(9, 64, 8));
        assertEquals(0, w.getBlockLight(8, 63, 8), "stone is opaque");
        assertEquals(15, w.getSkyLight(0, 64, 0));
        assertEquals(0, w.getSkyLight(0, 40, 0));
        assertMatchesReference(w, "flat");
    }

    @Test
    void randomTerrainLightingEditsAndRelight() {
        final Random r = new Random(1234);
        final SyntheticWorld w = new SyntheticWorld();
        final int radius = 2;
        for (int cx = -radius; cx <= radius; ++cx) {
            for (int cz = -radius; cz <= radius; ++cz) {
                w.addChunk(cx, cz);
            }
        }
        generate(w, r, radius);

        // load and light in a random order: each chunk's edges are checked against lit neighbours
        final List<SyntheticWorld.Chunk> order = new ArrayList<>(w.chunks.values());
        Collections.shuffle(order, r);
        for (final SyntheticWorld.Chunk c : order) {
            w.lightChunk(c);
        }
        assertMatchesReference(w, "initial light");

        // edits: batches of changes, flushed like a server tick
        final int[] ids = {AIR, STONE, GLASS, WATER, LEAVES, GLOWSTONE, TORCH, LAVA, ICE};
        for (int batch = 0; batch < 12; ++batch) {
            for (int i = 0; i < 25; ++i) {
                final int x = r.nextInt(radius * 32 + 16) - radius * 16;
                final int z = r.nextInt(radius * 32 + 16) - radius * 16;
                final int y = 30 + r.nextInt(70);
                w.setBlock(x, y, z, ids[r.nextInt(ids.length)]);
            }
            // a whole section cleared or filled now and then (emptiness changes)
            if (batch % 4 == 3) {
                final int cx = r.nextInt(2 * radius + 1) - radius, cz = r.nextInt(2 * radius + 1) - radius;
                final int sy = 5 + r.nextInt(2);
                final int id = r.nextBoolean() ? AIR : STONE;
                for (int i = 0; i < 4096; ++i) {
                    w.setBlock((cx << 4) | (i & 15), (sy << 4) | (i >>> 8), (cz << 4) | ((i >>> 4) & 15), id);
                }
            }
            w.flush();
            assertMatchesReference(w, "after edit batch " + batch);
        }

        // relight everything from scratch: same result
        final LongArrayList keys = new LongArrayList();
        for (final SyntheticWorld.Chunk c : w.chunks.values()) {
            keys.add(SyntheticWorld.key(c.x, c.z));
        }
        final LongOpenHashSet set = new LongOpenHashSet(keys);
        w.skyEngine.relightChunks(keys.toLongArray(), set, null, null);
        w.blockEngine.relightChunks(keys.toLongArray(), set, null, null);
        assertMatchesReference(w, "relight");
    }

    private static void generate(final SyntheticWorld w, final Random r, final int radius) {
        final int min = -radius * 16, max = radius * 16 + 15;
        for (int x = min; x <= max; ++x) {
            for (int z = min; z <= max; ++z) {
                final int h = 40 + (int)(12 * Math.sin(x * 0.11) * Math.cos(z * 0.07)) + r.nextInt(3);
                for (int y = 0; y <= h; ++y) {
                    w.setBlockRaw(x, y, z, STONE);
                }
                if (h < 38) {
                    for (int y = h + 1; y <= 38; ++y) {
                        w.setBlockRaw(x, y, z, WATER);
                    }
                }
                // caves
                if (r.nextInt(6) == 0) {
                    for (int y = 20; y < 20 + r.nextInt(8); ++y) {
                        w.setBlockRaw(x, y, z, AIR);
                    }
                }
            }
        }
        // trees (leaves canopies), light sources above and below ground, glass, ice
        for (int i = 0; i < 40; ++i) {
            final int x = min + 2 + r.nextInt(max - min - 4), z = min + 2 + r.nextInt(max - min - 4);
            final int base = 60 + r.nextInt(4);
            for (int dx = -2; dx <= 2; ++dx) {
                for (int dz = -2; dz <= 2; ++dz) {
                    for (int dy = 0; dy < 3; ++dy) {
                        w.setBlockRaw(x + dx, base + dy, z + dz, LEAVES);
                    }
                }
            }
        }
        final int[] extras = {GLOWSTONE, TORCH, LAVA, GLASS, ICE};
        for (int i = 0; i < 120; ++i) {
            w.setBlockRaw(min + r.nextInt(max - min + 1), 15 + r.nextInt(60), min + r.nextInt(max - min + 1), extras[r.nextInt(extras.length)]);
        }
    }

    /** Every position in every loaded chunk: block and sky light equal the exact fixpoint. */
    private static void assertMatchesReference(final SyntheticWorld w, final String what) {
        final SyntheticWorld.Area a = w.area();
        final byte[] block = w.referenceLight(a, false);
        final byte[] sky = w.referenceLight(a, true);
        int blockBad = 0, skyBad = 0;
        final StringBuilder first = new StringBuilder();
        for (final SyntheticWorld.Chunk c : w.chunks.values()) {
            for (int lx = 0; lx < 16; ++lx) {
                for (int lz = 0; lz < 16; ++lz) {
                    final int x = (c.x << 4) | lx, z = (c.z << 4) | lz;
                    for (int y = MIN_Y; y <= MAX_Y; ++y) {
                        final int idx = a.index(x, y, z);
                        final int eb = w.getBlockLight(x, y, z), es = w.getSkyLight(x, y, z);
                        if (eb != block[idx] && blockBad++ < 5) {
                            first.append(String.format("%n  block (%d,%d,%d): engine %d, expected %d", x, y, z, eb, block[idx]));
                        }
                        if (es != sky[idx] && skyBad++ < 5) {
                            first.append(String.format("%n  sky (%d,%d,%d): engine %d, expected %d", x, y, z, es, sky[idx]));
                        }
                    }
                }
            }
        }
        assertTrue(blockBad == 0 && skyBad == 0,
                what + ": " + blockBad + " block and " + skyBad + " sky light mismatches" + first);
    }
}
