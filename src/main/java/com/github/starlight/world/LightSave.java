package com.github.starlight.world;

import com.github.starlight.Starlight;
import com.github.starlight.light.LightChunk;
import com.github.starlight.light.LightWorld;
import com.github.starlight.light.SWMRNibbleArray;
import com.github.starlight.light.StarLightEngine;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Starlight light in chunk NBT (Moonrise's SaveUtil): format version, every light section's state,
 * and data only where vanilla's arrays can't hold it
 */
public final class LightSave {

    public static final String TAG = "starlight";
    private static final int VERSION = 1;
    private static final int SECTIONS = LightWorld.TOTAL_LIGHT_SECTIONS;
    // SWMRNibbleArray save states
    private static final int NULL = 0, INIT = 2;

    private LightSave() {}

    public static void write(final Chunk chunk, final boolean hasSky, final NBTTagCompound level) {
        final LightChunk lc = (LightChunk)chunk;
        if (!lc.starlight$isLightReady()) {
            return; // not lit yet: relit when it loads
        }
        try {
            // Vanilla stores the section light arrays by reference and the file is written later on
            // the IO thread: light updates in between would end up on disk with older blocks and
            // tag. Snapshot them now, while they are consistent (queued changes were just applied).
            final NBTTagList sections = level.getTagList("Sections", 10);
            for (int i = 0; i < sections.tagCount(); ++i) {
                final NBTTagCompound section = sections.getCompoundTagAt(i);
                for (final String key : new String[] {"BlockLight", "SkyLight"}) {
                    if (section.hasKey(key, 7)) {
                        section.setByteArray(key, section.getByteArray(key).clone());
                    }
                }
            }
            final NBTTagCompound tag = new NBTTagCompound();
            final int[] states = new int[SECTIONS * 2];
            writeLayer(chunk, lc.starlight$getBlockNibbles(), false, states, 0, tag);
            if (hasSky) {
                writeLayer(chunk, lc.starlight$getSkyNibbles(), true, states, SECTIONS, tag);
            }
            tag.setIntArray("States", states);
            // edge versions (see WorldLight.edgesToCheck); the caller made the chunk consistent
            final WorldLight.StarlightChunkState state = (WorldLight.StarlightChunkState)chunk;
            tag.setIntArray("EdgeVersions", toInts(state.starlight$edgeVersions()));
            tag.setIntArray("EdgeRecords", toInts(state.starlight$edgeRecords()));
            tag.setBoolean("Sky", hasSky);
            tag.setInteger("Version", VERSION); // last: only complete data is marked valid
            level.setTag(TAG, tag);
        } catch (final Throwable t) {
            Starlight.LOGGER.warn("[Starlight] could not save light of chunk {},{}; it will be relit on load", chunk.x, chunk.z, t);
        }
    }

    private static void writeLayer(final Chunk chunk, final SWMRNibbleArray[] nibbles, final boolean sky,
                                   final int[] states, final int offset, final NBTTagCompound tag) {
        for (int i = 0; i < SECTIONS; ++i) {
            final SWMRNibbleArray.SaveState save = nibbles[i] == null ? null : nibbles[i].getSaveState(false);
            if (save == null) {
                states[offset + i] = NULL;
                continue;
            }
            states[offset + i] = save.state();
            if (save.data() != null && !(save.state() == INIT && vanillaSection(chunk, i - 1) != null)) {
                tag.setByteArray((sky ? "S" : "B") + (i - 1), save.data().clone());
            }
        }
    }

    /** Restore saved light into a freshly read chunk; false (nothing changed) if there is none or it doesn't fit. */
    public static boolean read(final Chunk chunk, final boolean hasSky, final NBTTagCompound level) {
        if (!level.hasKey(TAG, 10)) {
            return false;
        }
        final NBTTagCompound tag = level.getCompoundTag(TAG);
        final int[] states = tag.getIntArray("States");
        if (tag.getInteger("Version") != VERSION || states.length != SECTIONS * 2 || tag.getBoolean("Sky") != hasSky) {
            return false;
        }
        try {
            final SWMRNibbleArray[] block = readLayer(chunk, states, 0, false, tag);
            final SWMRNibbleArray[] sky = hasSky ? readLayer(chunk, states, SECTIONS, true, tag) : StarLightEngine.getFilledEmptyLight();
            if (block == null || sky == null) {
                return false;
            }
            final LightChunk lc = (LightChunk)chunk;
            lc.starlight$setBlockNibbles(block);
            lc.starlight$setSkyNibbles(sky);
            final WorldLight.StarlightChunkState state = (WorldLight.StarlightChunkState)chunk;
            final long[] versions = longArray(tag, "EdgeVersions"), records = longArray(tag, "EdgeRecords");
            final boolean hasEdges = versions != null && records != null && versions[0] != 0 && versions[1] != 0 && versions[2] != 0 && versions[3] != 0;
            state.starlight$setEdgeUpgrade(!hasEdges);
            if (hasEdges) {
                // else: fresh versions, unknown records (every side checked)
                System.arraycopy(versions, 0, state.starlight$edgeVersions(), 0, 4);
                System.arraycopy(records, 0, state.starlight$edgeRecords(), 0, 4);
            }
            return true;
        } catch (final Throwable t) {
            Starlight.LOGGER.warn("[Starlight] could not read saved light of chunk {},{}; it will be relit", chunk.x, chunk.z, t);
            return false;
        }
    }

    private static SWMRNibbleArray[] readLayer(final Chunk chunk, final int[] states, final int offset, final boolean sky,
                                               final NBTTagCompound tag) {
        final SWMRNibbleArray[] nibbles = new SWMRNibbleArray[SECTIONS];
        for (int i = 0; i < SECTIONS; ++i) {
            final int state = states[offset + i];
            final String key = (sky ? "S" : "B") + (i - 1);
            byte[] data = null;
            if (tag.hasKey(key, 7)) {
                data = tag.getByteArray(key).clone();
            } else if (state == INIT) {
                final ExtendedBlockStorage ebs = vanillaSection(chunk, i - 1);
                if (ebs == null || (sky && ebs.getSkyLight() == null)) {
                    return null; // saved as "in the vanilla arrays" but the section is gone: relight
                }
                data = (sky ? ebs.getSkyLight() : ebs.getBlockLight()).getData().clone();
            }
            if (data != null && data.length != SWMRNibbleArray.ARRAY_SIZE) {
                return null;
            }
            nibbles[i] = data == null && state > INIT ? null : SWMRNibbleArray.of(data, state);
            if (nibbles[i] == null) {
                return null; // hidden without data: inconsistent
            }
        }
        return nibbles;
    }

    // 1.12's NBTTagLongArray has no getter: 4 longs as 8 ints (high, low)
    private static int[] toInts(final long[] v) {
        final int[] r = new int[8];
        for (int i = 0; i < 4; ++i) {
            r[2 * i] = (int)(v[i] >>> 32);
            r[2 * i + 1] = (int)v[i];
        }
        return r;
    }

    private static long[] longArray(final NBTTagCompound tag, final String key) {
        final int[] a = tag.getIntArray(key);
        if (a.length != 8) {
            return null;
        }
        final long[] r = new long[4];
        for (int i = 0; i < 4; ++i) {
            r[i] = ((long)a[2 * i] << 32) | (a[2 * i + 1] & 0xFFFFFFFFL);
        }
        return r;
    }

    private static ExtendedBlockStorage vanillaSection(final Chunk chunk, final int sectionY) {
        return sectionY < 0 || sectionY > 15 ? null : chunk.getBlockStorageArray()[sectionY];
    }
}
