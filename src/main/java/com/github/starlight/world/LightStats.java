package com.github.starlight.world;

import com.github.starlight.Starlight;

/** Chunk lighting counters, logged every 1000 chunks lit (server thread). */
public final class LightStats {

    private static long chunks, nanos, loaded, loadNanos;

    private LightStats() {}

    static void chunkLit(final long elapsedNanos) {
        ++chunks;
        nanos += elapsedNanos;
        if (chunks % 1000 == 0) {
            log();
        }
    }

    static void chunkLoaded(final long elapsedNanos) {
        ++loaded;
        loadNanos += elapsedNanos;
        if (loaded % 1000 == 0) {
            log();
        }
    }

    private static void log() {
        Starlight.LOGGER.info("[Starlight] {} chunks lit ({} ms average), {} loaded with saved light ({} ms average)",
                chunks, String.format("%.2f", chunks == 0 ? 0 : nanos / 1e6 / chunks),
                loaded, String.format("%.2f", loaded == 0 ? 0 : loadNanos / 1e6 / loaded));
    }
}
