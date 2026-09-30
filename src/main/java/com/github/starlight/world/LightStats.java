package com.github.starlight.world;

import com.github.starlight.Starlight;

/** Chunk lighting counters, logged every 1000 chunks lit (server thread). */
public final class LightStats {

    private static long chunks;
    private static long nanos;

    private LightStats() {}

    static void chunkLit(final long elapsedNanos) {
        ++chunks;
        nanos += elapsedNanos;
        if (chunks % 1000 == 0) {
            Starlight.LOGGER.info("[Starlight] {} chunks lit, {} ms average", chunks, String.format("%.2f", nanos / 1e6 / chunks));
        }
    }
}
