package com.github.starlight.light;

/**
 * A chunk as the light engine sees it: position, light-readiness and Starlight's per-chunk
 * light state. Implemented by 1.12 {@code Chunk} through a mixin, and by synthetic chunks in
 * tests. Light arrays cover sections -1..16 (index = sectionY + 1); emptiness maps cover the
 * 16 block sections (index = sectionY).
 */
public interface LightChunk {

    int starlight$chunkX();

    int starlight$chunkZ();

    /** Whether the chunk's light is complete, so its data may be read and propagated into by neighbours. */
    boolean starlight$isLightReady();

    SWMRNibbleArray[] starlight$getBlockNibbles();

    void starlight$setBlockNibbles(SWMRNibbleArray[] nibbles);

    SWMRNibbleArray[] starlight$getSkyNibbles();

    void starlight$setSkyNibbles(SWMRNibbleArray[] nibbles);

    boolean[] starlight$getBlockEmptinessMap();

    void starlight$setBlockEmptinessMap(boolean[] map);

    boolean[] starlight$getSkyEmptinessMap();

    void starlight$setSkyEmptinessMap(boolean[] map);
}
