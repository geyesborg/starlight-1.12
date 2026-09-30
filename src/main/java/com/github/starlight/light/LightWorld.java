package com.github.starlight.light;

/**
 * The world as the light engine sees it (Moonrise's LightChunkGetter plus the block property
 * hooks). Sections are opaque objects: 1.12 {@code ExtendedBlockStorage} in the game, synthetic
 * sections in tests. A null section is empty air.
 *
 * <p>Opacity and emission are asked per position (Forge's {@code getLightOpacity(world, pos)}
 * and {@code getLightValue(world, pos)} may depend on it: tile entities, fluidlogging, dynamic
 * lights). 1.12 has no per-face occlusion, so these two values are all propagation needs.</p>
 */
public interface LightWorld {

    /** Lowest and highest block section (1.12: 0..15); light sections extend one beyond each. */
    int MIN_SECTION = 0;
    int MAX_SECTION = 15;
    int MIN_LIGHT_SECTION = MIN_SECTION - 1;
    int MAX_LIGHT_SECTION = MAX_SECTION + 1;
    int TOTAL_SECTIONS = MAX_SECTION - MIN_SECTION + 1;
    int TOTAL_LIGHT_SECTIONS = MAX_LIGHT_SECTION - MIN_LIGHT_SECTION + 1;

    /** The loaded chunk at these coordinates, or null. */
    LightChunk getChunkForLighting(int chunkX, int chunkZ);

    /** Block section {@code sectionY} (MIN_SECTION..MAX_SECTION) of the chunk, or null when it has none. */
    Object getSection(LightChunk chunk, int sectionY);

    /** Whether a non-null section holds only air. */
    boolean isEmpty(Object section);

    /**
     * Light opacity (0..255) of the block at {@code localIndex} ({@code x | z << 4 | y << 8}) of a
     * non-null section, at the given world position. Propagation subtracts {@code max(1, opacity)}.
     */
    int getOpacity(Object section, int localIndex, int worldX, int worldY, int worldZ);

    /** Emitted block light (0..15) of the block at {@code localIndex} of a non-null section. */
    int getEmission(Object section, int localIndex, int worldX, int worldY, int worldZ);

    /** False only when the section certainly contains no light emitters (source scans skip it). */
    default boolean mayHaveEmission(Object section) {
        return true;
    }

    boolean isClientSide();

    boolean hasSkyLight();

    /** Light values of this section changed (visible data updated): re-render, send, save. */
    void onLightUpdate(boolean sky, int chunkX, int chunkY, int chunkZ);
}
