/*
 * Ported from Moonrise (Tuinity/Moonrise, ca.spottedleaf.moonrise.patches.starlight) by Spottedleaf,
 * licensed under the GNU General Public License v3.0. Changes for Minecraft 1.12.2: see git history.
 */
package com.github.starlight.light;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import net.minecraft.world.chunk.NibbleArray;

// SWMR -> Single Writer Multi Reader Nibble Array
public final class SWMRNibbleArray {

    /*
     * Null nibble - nibble does not exist, and should not be written to. Just like vanilla - null
     * nibbles are always 0 - and they are never written to directly. Only initialised/uninitialised
     * nibbles can be written to.
     *
     * Uninitialised nibble - They are all 0, but the backing array isn't initialised.
     *
     * Initialised nibble - Has light data.
     */

    private static final int INIT_STATE_NULL   = 0; // null
    private static final int INIT_STATE_UNINIT = 1; // uninitialised
    private static final int INIT_STATE_INIT   = 2; // initialised
    private static final int INIT_STATE_HIDDEN = 3; // initialised, but conversion to Vanilla data should be treated as if NULL

    public static final int ARRAY_SIZE = 16 * 16 * 16 / (8/4); // blocks / bytes per block
    // this allows us to maintain only 1 byte array when we're not updating
    private static final ThreadLocal<ArrayDeque<byte[]>> WORKING_BYTES_POOL = ThreadLocal.withInitial(ArrayDeque::new);

    private static byte[] allocateBytes() {
        final byte[] inPool = WORKING_BYTES_POOL.get().pollFirst();
        if (inPool != null) {
            return inPool;
        }

        return new byte[ARRAY_SIZE];
    }

    // 1.12: one shared all-15 array for the sky light of air sections above the terrain; writers copy it first
    private static final byte[] FULL = new byte[ARRAY_SIZE];
    static {
        Arrays.fill(FULL, (byte)-1);
    }

    private static boolean isAllFull(final byte[] data) {
        for (int i = 0, len = data.length / Long.BYTES; i < len; ++i) {
            if (-1L != (long)LONG_VIEW.get(data, i << 3)) {
                return false;
            }
        }
        return true;
    }

    // Bounded: a large relight can free many arrays at once, and a thread-local pool never shrinks
    private static final int MAX_POOLED = 256;

    private static void freeBytes(final byte[] bytes) {
        final ArrayDeque<byte[]> pool = WORKING_BYTES_POOL.get();
        if (pool.size() < MAX_POOLED) {
            pool.addFirst(bytes);
        }
    }

    // 1.12: NibbleArray always has storage (no "empty" layer as modern DataLayer)
    public static SWMRNibbleArray fromVanilla(final NibbleArray nibble) {
        if (nibble == null) {
            return new SWMRNibbleArray(null, true);
        } else {
            return new SWMRNibbleArray(nibble.getData().clone()); // make sure we don't write to the parameter later
        }
    }

    private int stateUpdating;
    private volatile int stateVisible;

    private byte[] storageUpdating;
    private boolean updatingDirty; // only returns whether storageUpdating is dirty
    private volatile byte[] storageVisible;
    // 1.12: a vanilla section light array that holds the visible data (see bindVisibleStorage), or null
    private byte[] boundVisible;
    // whether the last updateVisible published written light values or dropped existing data
    // (as opposed to a state-only change, which loading recomputes from section emptiness)
    private boolean lastUpdateChangedData;

    public SWMRNibbleArray() {
        this(null, false); // lazy init
    }

    public SWMRNibbleArray(final byte[] bytes) {
        this(bytes, false);
    }

    public SWMRNibbleArray(final byte[] bytes, final boolean isNullNibble) {
        if (bytes != null && bytes.length != ARRAY_SIZE) {
            throw new IllegalArgumentException("Data of wrong length: " + bytes.length);
        }
        this.stateVisible = this.stateUpdating = bytes == null ? (isNullNibble ? INIT_STATE_NULL : INIT_STATE_UNINIT) : INIT_STATE_INIT;
        this.storageUpdating = this.storageVisible = bytes;
    }

    public SWMRNibbleArray(final byte[] bytes, final int state) {
        if (bytes != null && bytes.length != ARRAY_SIZE) {
            throw new IllegalArgumentException("Data of wrong length: " + bytes.length);
        }
        if (bytes == null && (state == INIT_STATE_INIT || state == INIT_STATE_HIDDEN)) {
            throw new IllegalArgumentException("Data cannot be null and have state be initialised");
        }
        this.stateUpdating = this.stateVisible = state;
        this.storageUpdating = this.storageVisible = bytes;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("State: ");
        switch (this.stateVisible) {
            case INIT_STATE_NULL:
                stringBuilder.append("null");
                break;
            case INIT_STATE_UNINIT:
                stringBuilder.append("uninitialised");
                break;
            case INIT_STATE_INIT:
                stringBuilder.append("initialised");
                break;
            case INIT_STATE_HIDDEN:
                stringBuilder.append("hidden");
                break;
            default:
                stringBuilder.append("unknown");
                break;
        }
        stringBuilder.append("\nData:\n");

        final byte[] data = this.storageVisible;
        if (data != null) {
            for (int i = 0; i < 4096; ++i) {
                // Copied from NibbleArray#toString
                final int level = ((data[i >>> 1] >>> ((i & 1) << 2)) & 0xF);

                stringBuilder.append(Integer.toHexString(level));
                if ((i & 15) == 15) {
                    stringBuilder.append("\n");
                }

                if ((i & 255) == 255) {
                    stringBuilder.append("\n");
                }
            }
        } else {
            stringBuilder.append("null");
        }

        return stringBuilder.toString();
    }

    public SaveState getSaveState() {
        return this.getSaveState(true);
    }

    public SaveState getSaveState(final boolean copy) {
        synchronized (this) {
            final int state = this.stateVisible;
            final byte[] data = this.storageVisible;
            if (state == INIT_STATE_NULL) {
                return null;
            }
            if (state == INIT_STATE_UNINIT) {
                return new SaveState(null, state);
            }
            final boolean zero = isAllZero(data);
            if (zero) {
                return state == INIT_STATE_INIT ? new SaveState(null, INIT_STATE_UNINIT) : null;
            } else {
                return new SaveState(copy ? data.clone() : data, state);
            }
        }
    }

    private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private static boolean isAllZero(final byte[] data) {
        final int len = data.length / Long.BYTES; // assume divisible

        for (int i = 0; i < len; ++i) {
            if (0L != (long)LONG_VIEW.get(data, i << 3)) {
                return false;
            }
        }

        return true;
    }

    // operation type: updating on src, updating on other
    public void extrudeLower(final SWMRNibbleArray other) {
        if (other.stateUpdating == INIT_STATE_NULL) {
            throw new IllegalArgumentException();
        }

        if (other.storageUpdating == null) {
            this.setUninitialised();
            return;
        }

        final byte[] src = other.storageUpdating;
        final byte[] into;

        if (!this.updatingDirty) {
            if (this.storageUpdating != null) {
                into = this.storageUpdating = allocateBytes();
            } else {
                this.storageUpdating = into = allocateBytes();
                this.stateUpdating = INIT_STATE_INIT;
            }
            this.updatingDirty = true;
        } else {
            into = this.storageUpdating;
        }

        final int start = 0;
        final int end = (15 | (15 << 4)) >>> 1;

        /* x | (z << 4) | (y << 8) */
        for (int y = 0; y <= 15; ++y) {
            System.arraycopy(src, start, into, y << (8 - 1), end - start + 1);
        }
    }

    // operation type: updating
    public void setFull() {
        if (this.stateUpdating != INIT_STATE_HIDDEN) {
            this.stateUpdating = INIT_STATE_INIT;
        }
        Arrays.fill(this.storageUpdating == null || !this.updatingDirty ? this.storageUpdating = allocateBytes() : this.storageUpdating, (byte)-1);
        this.updatingDirty = true;
    }

    // operation type: updating
    public void setZero() {
        if (this.stateUpdating != INIT_STATE_HIDDEN) {
            this.stateUpdating = INIT_STATE_INIT;
        }
        Arrays.fill(this.storageUpdating == null || !this.updatingDirty ? this.storageUpdating = allocateBytes() : this.storageUpdating, (byte)0);
        this.updatingDirty = true;
    }

    // operation type: updating
    public void setNonNull() {
        if (this.stateUpdating == INIT_STATE_HIDDEN) {
            this.stateUpdating = INIT_STATE_INIT;
            return;
        }
        if (this.stateUpdating != INIT_STATE_NULL) {
            return;
        }
        this.stateUpdating = INIT_STATE_UNINIT;
    }

    // operation type: updating
    public void setNull() {
        this.stateUpdating = INIT_STATE_NULL;
        if (this.updatingDirty && this.storageUpdating != null) {
            freeBytes(this.storageUpdating);
        }
        this.storageUpdating = null;
        this.updatingDirty = false;
    }

    // operation type: updating
    public void setUninitialised() {
        this.stateUpdating = INIT_STATE_UNINIT;
        if (this.storageUpdating != null && this.updatingDirty) {
            freeBytes(this.storageUpdating);
        }
        this.storageUpdating = null;
        this.updatingDirty = false;
    }

    // operation type: updating
    public void setHidden() {
        if (this.stateUpdating == INIT_STATE_HIDDEN) {
            return;
        }
        if (this.stateUpdating != INIT_STATE_INIT) {
            this.setNull();
        } else {
            this.stateUpdating = INIT_STATE_HIDDEN;
        }
    }

    // operation type: updating
    public boolean isDirty() {
        return this.stateUpdating != this.stateVisible || this.updatingDirty;
    }

    // operation type: updating
    public boolean isNullNibbleUpdating() {
        return this.stateUpdating == INIT_STATE_NULL;
    }

    // operation type: visible
    public boolean isNullNibbleVisible() {
        return this.stateVisible == INIT_STATE_NULL;
    }

    // opeartion type: updating
    public boolean isUninitialisedUpdating() {
        return this.stateUpdating == INIT_STATE_UNINIT;
    }

    // operation type: visible
    public boolean isUninitialisedVisible() {
        return this.stateVisible == INIT_STATE_UNINIT;
    }

    // operation type: updating
    public boolean isInitialisedUpdating() {
        return this.stateUpdating == INIT_STATE_INIT;
    }

    // operation type: visible
    public boolean isInitialisedVisible() {
        return this.stateVisible == INIT_STATE_INIT;
    }

    // operation type: updating
    public boolean isHiddenUpdating() {
        return this.stateUpdating == INIT_STATE_HIDDEN;
    }

    // operation type: updating
    public boolean isHiddenVisible() {
        return this.stateVisible == INIT_STATE_HIDDEN;
    }

    // operation type: updating
    private void swapUpdatingAndMarkDirty() {
        if (this.updatingDirty) {
            return;
        }

        if (this.storageUpdating == null) {
            this.storageUpdating = allocateBytes();
            Arrays.fill(this.storageUpdating, (byte)0);
        } else {
            System.arraycopy(this.storageUpdating, 0, this.storageUpdating = allocateBytes(), 0, ARRAY_SIZE);
        }

        if (this.stateUpdating != INIT_STATE_HIDDEN) {
            this.stateUpdating = INIT_STATE_INIT;
        }
        this.updatingDirty = true;
    }

    // operation type: updating
    public boolean updateVisible() {
        if (!this.isDirty()) {
            return false;
        }

        synchronized (this) {
            this.lastUpdateChangedData = this.updatingDirty
                    || (this.storageVisible != null && (this.stateUpdating == INIT_STATE_NULL || this.stateUpdating == INIT_STATE_UNINIT));
            if (this.stateUpdating == INIT_STATE_NULL || this.stateUpdating == INIT_STATE_UNINIT) {
                if (this.boundVisible != null && this.storageVisible != null) {
                    Arrays.fill(this.boundVisible, (byte)0); // vanilla sees no data as zeros
                }
                this.storageVisible = null;
            } else {
                if (this.boundVisible == null && isAllFull(this.storageUpdating)) {
                    // share the constant instead of keeping a private all-15 copy
                    if (this.storageUpdating != this.storageVisible && this.storageUpdating != FULL) {
                        freeBytes(this.storageUpdating);
                    }
                    this.storageUpdating = this.storageVisible = FULL;
                    this.updatingDirty = false;
                    this.stateVisible = this.stateUpdating;
                    return true;
                }
                if (this.storageVisible == FULL) {
                    this.storageVisible = null; // never write into the shared array
                }
                if (this.storageVisible == null) {
                    if (this.boundVisible != null) {
                        System.arraycopy(this.storageUpdating, 0, this.boundVisible, 0, ARRAY_SIZE);
                        this.storageVisible = this.boundVisible;
                    } else {
                        this.storageVisible = this.storageUpdating.clone();
                    }
                } else {
                    if (this.storageUpdating != this.storageVisible) {
                        System.arraycopy(this.storageUpdating, 0, this.storageVisible, 0, ARRAY_SIZE);
                    }
                }

                if (this.storageUpdating != this.storageVisible) {
                    freeBytes(this.storageUpdating);
                }
                this.storageUpdating = this.storageVisible;
            }
            this.updatingDirty = false;
            this.stateVisible = this.stateUpdating;
        }

        return true;
    }

    // operation type: visible
    public NibbleArray toVanillaNibble() {
        synchronized (this) {
            return switch (this.stateVisible) {
                case INIT_STATE_HIDDEN, INIT_STATE_NULL -> null;
                case INIT_STATE_UNINIT -> new NibbleArray();
                case INIT_STATE_INIT -> new NibbleArray(this.storageVisible.clone());
                default -> throw new IllegalStateException();
            };
        }
    }

    // operation type: updating (owner thread)
    public boolean lastUpdateChangedData() {
        return this.lastUpdateChangedData;
    }

    // operation type: updating (owner thread)
    /**
     * 1.12: the vanilla section array holds the visible data instead of a copy; updates still go to
     * a separate array and are published by updateVisible
     */
    public boolean bindVisibleStorage(final byte[] into) {
        synchronized (this) {
            final byte[] data = this.storageVisible;
            if (this.boundVisible == into && (data == null || data == into)) {
                return false;
            }
            this.boundVisible = into;
            if (data == null) {
                if (isAllZero(into)) {
                    return false;
                }
                Arrays.fill(into, (byte)0);
                return true;
            }
            final boolean changed = !Arrays.equals(data, into);
            if (changed) {
                System.arraycopy(data, 0, into, 0, ARRAY_SIZE);
            }
            if (this.storageUpdating == data) {
                this.storageUpdating = into;
            }
            this.storageVisible = into;
            return changed;
        }
    }

    /* x | (z << 4) | (y << 8) */

    // operation type: updating
    public int getUpdating(final int x, final int y, final int z) {
        return this.getUpdating((x & 15) | ((z & 15) << 4) | ((y & 15) << 8));
    }

    // operation type: updating
    public int getUpdating(final int index) {
        // indices range from 0 -> 4096
        final byte[] bytes = this.storageUpdating;
        if (bytes == null) {
            return 0;
        }
        final byte value = bytes[index >>> 1];

        // if we are an even index, we want lower 4 bits
        // if we are an odd index, we want upper 4 bits
        return ((value >>> ((index & 1) << 2)) & 0xF);
    }

    // operation type: visible
    public int getVisible(final int x, final int y, final int z) {
        return this.getVisible((x & 15) | ((z & 15) << 4) | ((y & 15) << 8));
    }

    // operation type: visible
    public int getVisible(final int index) {
        // indices range from 0 -> 4096
        final byte[] visibleBytes = this.storageVisible;
        if (visibleBytes == null) {
            return 0;
        }
        final byte value = visibleBytes[index >>> 1];

        // if we are an even index, we want lower 4 bits
        // if we are an odd index, we want upper 4 bits
        return ((value >>> ((index & 1) << 2)) & 0xF);
    }

    // operation type: updating
    public void set(final int x, final int y, final int z, final int value) {
        this.set((x & 15) | ((z & 15) << 4) | ((y & 15) << 8), value);
    }

    // operation type: updating
    public void set(final int index, final int value) {
        if (!this.updatingDirty) {
            this.swapUpdatingAndMarkDirty();
        }
        final int shift = (index & 1) << 2;
        final int i = index >>> 1;

        this.storageUpdating[i] = (byte)((this.storageUpdating[i] & (0xF0 >>> shift)) | (value << shift));
    }

    // operation type: visible
    /** Exact copy of the visible state and data (no folding as in save states) */
    public SaveState getVisibleState() {
        synchronized (this) {
            final int state = this.stateVisible;
            if (state == INIT_STATE_NULL) {
                return null;
            }
            final byte[] data = this.storageVisible;
            return new SaveState(data == null ? null : data.clone(), state);
        }
    }

    /** All-15 data shares the constant array, copied before any write */
    public static SWMRNibbleArray of(final byte[] data, final int state) {
        return new SWMRNibbleArray(data != null && state == INIT_STATE_INIT && isAllFull(data) ? FULL : data, state);
    }

    public int[] storageKinds() {
        synchronized (this) {
            final int[] r = new int[3];
            for (final byte[] a : new byte[][] {this.storageVisible, this.storageUpdating == this.storageVisible ? null : this.storageUpdating}) {
                if (a == null) continue;
                if (a == FULL) ++r[2]; else if (a == this.boundVisible) ++r[1]; else ++r[0];
            }
            return r;
        }
    }

    public static int poolSize() {
        return WORKING_BYTES_POOL.get().size();
    }

    public record SaveState(byte[] data, int state) {
    }
}
