package com.github.starlight.world;

import com.github.starlight.Starlight;

/**
 * Chunk lighting counters (server thread): chunks lit as new (generated, or relit with no
 * neighbour holding saved light), lit with full edge checks, and loaded with saved light;
 * logged every 500.
 */
public final class LightStats {

    private static final long[] COUNT = new long[3], NANOS = new long[3];
    private static final String[] NAMES = {"lit as new", "lit with edge checks", "loaded with saved light"};
    private static long total;

    private LightStats() {}

    static void chunkLit(final boolean fresh, final long elapsedNanos) {
        add(fresh ? 0 : 1, elapsedNanos);
    }

    static void chunkLoaded(final long elapsedNanos) {
        add(2, elapsedNanos);
    }

    private static void add(final int kind, final long elapsedNanos) {
        ++COUNT[kind];
        NANOS[kind] += elapsedNanos;
        if (++total % 500 == 0) {
            final StringBuilder sb = new StringBuilder("[Starlight] chunks:");
            for (int i = 0; i < 3; ++i) {
                sb.append(String.format(" %d %s (%.2f ms avg)%s", COUNT[i], NAMES[i],
                        COUNT[i] == 0 ? 0.0 : NANOS[i] / 1e6 / COUNT[i], i < 2 ? "," : ""));
            }
            Starlight.LOGGER.info(sb.toString());
        }
    }
}
