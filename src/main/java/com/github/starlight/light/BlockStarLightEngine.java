/*
 * Ported from Moonrise (Tuinity/Moonrise, ca.spottedleaf.moonrise.patches.starlight) by Spottedleaf,
 * licensed under the GNU General Public License v3.0. Changes for Minecraft 1.12.2: see git history.
 */
package com.github.starlight.light;

import it.unimi.dsi.fastutil.ints.IntArrayList;

public final class BlockStarLightEngine extends StarLightEngine {

    public BlockStarLightEngine(final LightWorld world) {
        super(false, world);
    }

    @Override
    protected boolean[] getEmptinessMap(final LightChunk chunk) {
        return chunk.starlight$getBlockEmptinessMap();
    }

    @Override
    protected void setEmptinessMap(final LightChunk chunk, final boolean[] to) {
        chunk.starlight$setBlockEmptinessMap(to);
    }

    @Override
    protected SWMRNibbleArray[] getNibblesOnChunk(final LightChunk chunk) {
        return chunk.starlight$getBlockNibbles();
    }

    @Override
    protected void setNibbles(final LightChunk chunk, final SWMRNibbleArray[] to) {
        chunk.starlight$setBlockNibbles(to);
    }

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble != null) {
            // de-initialisation is not as straightforward as with sky data, since deinit of block light is typically
            // because a block was removed - which can decrease light. with sky data, block breaking can only result
            // in increases, and thus the existing sky block check will actually correctly propagate light through
            // a null section. so in order to propagate decreases correctly, we can do a couple of things: not remove
            // the data section, or do edge checks on ALL axis (x, y, z). however I do not want edge checks running
            // for clients at all, as they are expensive. so we don't remove the section, but to maintain the appearence
            // of vanilla data management we "hide" them.
            nibble.setHidden();
        }
    }

    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.getChunkInCache(chunkX, chunkZ) == null) {
            return;
        }

        final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble == null) {
            if (!initRemovedNibbles) {
                throw new IllegalStateException();
            } else {
                this.setNibbleInCache(chunkX, chunkY, chunkZ, new SWMRNibbleArray());
            }
        } else {
            nibble.setNonNull();
        }
    }

    @Override
    protected void checkBlock(final int worldX, final int worldY, final int worldZ) {
        // blocks can change opacity
        // blocks can change emitted light
        // blocks can change direction of propagation

        final int encodeOffset = this.coordinateOffset;
        final int emittedMask = this.emittedLightMask;

        final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);
        final int emittedLevel = this.getEmission(worldX, worldY, worldZ) & emittedMask;

        this.setLightLevel(worldX, worldY, worldZ, emittedLevel);
        // this accounts for change in emitted light that would cause an increase
        if (emittedLevel != 0) {
            this.appendToIncreaseQueue(
                    ((worldX + (worldZ << 6) + (worldY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                            | (emittedLevel & 0xFL) << (6 + 6 + 16)
                            | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
            );
        }
        // this also accounts for a change in emitted light that would cause a decrease
        // this also accounts for the change of direction of propagation (i.e old block was full transparent, new block is full opaque or vice versa)
        // as it checks all neighbours (even if current level is 0)
        this.appendToDecreaseQueue(
                ((worldX + (worldZ << 6) + (worldY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                        | (currentLevel & 0xFL) << (6 + 6 + 16)
                        | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
        );
        // re-propagating neighbours (done by the decrease queue) will also account for opacity changes in this block
    }

    @Override
    protected int calculateLightValue(final int worldX, final int worldY, final int worldZ, final int expect) {
        int level = this.getEmission(worldX, worldY, worldZ) & this.emittedLightMask;

        if (level >= (15 - 1) || level > expect) {
            return level;
        }

        final int opacity = Math.max(1, this.getOpacity(worldX, worldY, worldZ));
        if (opacity >= 15) {
            return level;
        }

        final int sectionOffset = this.chunkSectionIndexOffset;
        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            final int offX = worldX + direction.x;
            final int offY = worldY + direction.y;
            final int offZ = worldZ + direction.z;

            final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;

            final int neighbourLevel = this.getLightLevel(sectionIndex, (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8));

            if ((neighbourLevel - 1) <= level) {
                // we know it wont affect the result.
                continue;
            }

            final int calculated = neighbourLevel - opacity;
            level = Math.max(calculated, level);
            if (level > expect) {
                return level;
            }
        }

        return level;
    }

    @Override
    protected void propagateBlockChanges(final LightChunk atChunk, final int[] positions, final int positionCount) {
        for (int i = 0; i < positionCount; ++i) {
            this.checkBlock(positions[3 * i], positions[3 * i + 1], positions[3 * i + 2]);
        }

        this.performLightDecrease();
    }

    /** Light sources of the chunk: world positions packed as x, y, z triples. */
    protected IntArrayList getSources(final LightChunk chunk) {
        final IntArrayList sources = new IntArrayList();

        final int offX = chunk.starlight$chunkX() << 4;
        final int offZ = chunk.starlight$chunkZ() << 4;

        for (int sectionY = this.minSection; sectionY <= this.maxSection; ++sectionY) {
            final Object section = this.world.getSection(chunk, sectionY);
            if (section == null || this.world.isEmpty(section) || !this.world.mayHaveEmission(section)) {
                // no sources in empty sections or sections without emitting blocks
                continue;
            }
            final int offY = sectionY << 4;

            for (int index = 0; index < (16 * 16 * 16); ++index) {
                // index = x | (z << 4) | (y << 8)
                final int x = offX | (index & 15);
                final int y = offY | (index >>> 8);
                final int z = offZ | ((index >>> 4) & 15);
                if (this.world.getEmission(section, index, x, y, z) == 0) {
                    continue;
                }
                sources.add(x);
                sources.add(y);
                sources.add(z);
            }
        }

        return sources;
    }

    @Override
    public void lightChunk(final LightChunk chunk, final boolean needsEdgeChecks) {
        // setup sources
        final int emittedMask = this.emittedLightMask;
        final IntArrayList positions = this.getSources(chunk);
        final int[] pos = positions.elements();
        for (int i = 0, len = positions.size(); i < len; i += 3) {
            final int x = pos[i], y = pos[i + 1], z = pos[i + 2];
            final int emittedLight = this.getEmission(x, y, z) & emittedMask;

            if (emittedLight <= this.getLightLevel(x, y, z)) {
                // some other source is brighter
                continue;
            }

            this.appendToIncreaseQueue(
                    ((x + (z << 6) + (y << (6 + 6)) + this.coordinateOffset) & ((1L << (6 + 6 + 16)) - 1))
                            | (emittedLight & 0xFL) << (6 + 6 + 16)
                            | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
            );

            // propagation wont set this for us
            this.setLightLevel(x, y, z, emittedLight);
        }

        if (needsEdgeChecks) {
            // not required to propagate here, but this will reduce the hit of the edge checks
            this.performLightIncrease();

            // verify neighbour edges
            this.checkChunkEdges(chunk, this.minLightSection, this.maxLightSection);
        } else {
            this.propagateNeighbourLevels(chunk, this.minLightSection, this.maxLightSection);

            this.performLightIncrease();
        }
    }
}
