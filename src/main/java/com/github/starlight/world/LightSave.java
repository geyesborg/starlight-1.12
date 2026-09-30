package com.github.starlight.world;

import com.github.starlight.Starlight;
import com.github.starlight.light.LightChunk;
import com.github.starlight.light.LightWorld;
import com.github.starlight.light.SWMRNibbleArray;
import com.github.starlight.light.StarLightEngine;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Starlight light in chunk NBT (Moonrise's SaveUtil for 1.12). A {@code starlight} compound in
 * the Level tag holds a format version, the state of every light section (-1..16, block then
 * sky) and explicit data only where the vanilla section arrays can't carry it: sections without
 * an ExtendedBlockStorage (including -1 and 16) and hidden block sections. Initialised data of
 * existing sections is read back from the vanilla arrays, which hold exactly Starlight's values
 * (they are mirrored). Vanilla's light arrays stay in the save, so the world still opens without
 * Starlight; chunks without the tag (vanilla, Alfheim) are relit on load.
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
            final NBTTagCompound tag = new NBTTagCompound();
            final int[] states = new int[SECTIONS * 2];
            writeLayer(chunk, lc.starlight$getBlockNibbles(), false, states, 0, tag);
            if (hasSky) {
                writeLayer(chunk, lc.starlight$getSkyNibbles(), true, states, SECTIONS, tag);
            }
            tag.setIntArray("States", states);
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

    private static ExtendedBlockStorage vanillaSection(final Chunk chunk, final int sectionY) {
        return sectionY < 0 || sectionY > 15 ? null : chunk.getBlockStorageArray()[sectionY];
    }
}
