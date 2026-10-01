package com.github.starlight.world;

import com.github.starlight.light.BlockStarLightEngine;
import com.github.starlight.light.LightChunk;
import com.github.starlight.light.LightWorld;
import com.github.starlight.light.SWMRNibbleArray;
import com.github.starlight.light.SkyStarLightEngine;
import com.github.starlight.light.StarLightEngine;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Starlight for one world (Moonrise's StarLightInterface): engines, change queue, readers, and the
 * vanilla array binding
 */
public final class WorldLight implements LightWorld {

    private final World world;
    private final boolean client;
    private final boolean hasSky;
    private final SkyStarLightEngine skyEngine;
    private final BlockStarLightEngine blockEngine;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    // Queued changes per chunk (key: ChunkPos.asLong): packed block positions and section emptiness
    private final Long2ObjectLinkedOpenHashMap<Pending> pending = new Long2ObjectLinkedOpenHashMap<>();
    private boolean propagating;

    private static final class Pending {
        final LongOpenHashSet positions = new LongOpenHashSet();
        Boolean[] sections;
    }

    public WorldLight(final World world) {
        this.world = world;
        this.client = world.isRemote;
        this.hasSky = world.provider.hasSkyLight();
        this.skyEngine = this.hasSky ? new SkyStarLightEngine(this) : null;
        this.blockEngine = new BlockStarLightEngine(this);
    }

    public synchronized void queueBlockChange(final BlockPos pos) {
        if (pos.getY() < 0 || pos.getY() > 255) {
            return;
        }
        this.pending.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), k -> new Pending())
                .positions.add(pos.toLong());
    }

    public synchronized void queueSectionChange(final int chunkX, final int sectionY, final int chunkZ, final boolean empty) {
        final Pending p = this.pending.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), k -> new Pending());
        if (p.sections == null) {
            p.sections = new Boolean[TOTAL_SECTIONS];
        }
        p.sections[sectionY] = empty;
    }

    public boolean hasPendingChanges() {
        return !this.pending.isEmpty();
    }

    /** Apply every queued change (end of tick, before saves and chunk packets). */
    public void propagateChanges() {
        if (this.propagating || this.pending.isEmpty()) {
            return;
        }
        this.propagating = true;
        try {
            while (true) {
                final long key;
                final Pending p;
                synchronized (this) {
                    if (this.pending.isEmpty()) {
                        break;
                    }
                    key = this.pending.firstLongKey();
                    p = this.pending.removeFirst();
                }
                final int chunkX = (int)key, chunkZ = (int)(key >>> 32); // ChunkPos.asLong: x low, z high
                final int[] positions = new int[p.positions.size() * 3];
                int n = 0;
                for (final LongIterator it = p.positions.iterator(); it.hasNext(); ) {
                    // BlockPos.toLong: x (26 bits) << 38 | y (12 bits) << 26 | z (26 bits), each signed
                    final long packed = it.nextLong();
                    positions[n++] = (int)(packed >> 38);
                    positions[n++] = (int)((packed << 26) >> 52);
                    positions[n++] = (int)((packed << 38) >> 38);
                }
                if (this.skyEngine != null) {
                    this.skyEngine.blocksChangedInChunk(chunkX, chunkZ, positions, n / 3, p.sections == null ? null : p.sections.clone());
                }
                this.blockEngine.blocksChangedInChunk(chunkX, chunkZ, positions, n / 3, p.sections == null ? null : p.sections.clone());
            }
        } finally {
            this.propagating = false;
        }
    }

    /** fresh: just generated (see StarLightEngine.light) */
    public void lightChunk(final Chunk chunk, final boolean fresh) {
        final LightChunk lc = (LightChunk)chunk;
        if (lc.starlight$isLightReady()) {
            return;
        }
        final long start = System.nanoTime();
        bumpEdges(chunk);
        Arrays.fill(((StarlightChunkState)chunk).starlight$edgeRecords(), 0L);
        final Boolean[] empty = this.blockEngine.getEmptySectionsForChunk(lc);
        if (this.skyEngine != null) {
            this.skyEngine.light(lc, empty.clone(), fresh);
        }
        this.blockEngine.light(lc, empty.clone(), fresh);
        ((StarlightChunkState)chunk).starlight$setKnownEmptiness(empty);
        ((StarlightChunkState)chunk).starlight$setLightReady(true);
        this.mirrorChunk(chunk, true);
        if (!this.client) {
            this.syncEdgeRecords(chunk);
            LightStats.chunkLit(fresh, System.nanoTime() - start);
        }
    }

    /**
     * Loaded with saved light: register section emptiness, then check edges only where a neighbour
     * may have changed
     */
    public void loadSavedLight(final Chunk chunk) {
        final LightChunk lc = (LightChunk)chunk;
        final long start = System.nanoTime();
        int edges = this.edgesToCheck(chunk); // before anything here changes light
        final long[] before = this.sideVersions(chunk);
        final Boolean[] empty = this.blockEngine.getEmptySectionsForChunk(lc);
        if (this.skyEngine != null) {
            this.skyEngine.forceHandleEmptySectionChanges(lc, empty.clone());
        }
        this.blockEngine.forceHandleEmptySectionChanges(lc, empty.clone());
        // registering emptiness can write light (sky data extruded into sections that had none, here
        // or in a neighbour): that is approximate at borders, so those sides are checked regardless
        edges |= this.changedSides(chunk, before);
        ((StarlightChunkState)chunk).starlight$setKnownEmptiness(empty);
        ((StarlightChunkState)chunk).starlight$setLightFromSave(true);
        ((StarlightChunkState)chunk).starlight$setLightReady(true);
        if (CHECK_ALL_EDGES || edges != 0) {
            final int mask = CHECK_ALL_EDGES ? 0xF : edges;
            if (this.skyEngine != null) {
                this.skyEngine.checkChunkEdges(chunk.x, chunk.z, mask);
            }
            this.blockEngine.checkChunkEdges(chunk.x, chunk.z, mask);
        }
        // saved without edge versions (older format): save once with them, so later loads can skip checks
        this.mirrorChunk(chunk, ((StarlightChunkState)chunk).starlight$needsEdgeUpgrade());
        ((StarlightChunkState)chunk).starlight$setEdgeUpgrade(false);
        this.syncEdgeRecords(chunk);
        LightStats.chunkLoaded(System.nanoTime() - start, Integer.bitCount(edges));
    }

    /**
     * Singleplayer client: copy the integrated server's finished light instead of computing it again
     */
    private void importLight(final Chunk chunk, final Chunk from) {
        final LightChunk lc = (LightChunk)chunk, src = (LightChunk)from;
        lc.starlight$setBlockNibbles(copyVisible(src.starlight$getBlockNibbles()));
        lc.starlight$setSkyNibbles(this.hasSky ? copyVisible(src.starlight$getSkyNibbles()) : StarLightEngine.getFilledEmptyLight());
        final Boolean[] empty = this.blockEngine.getEmptySectionsForChunk(lc);
        if (this.skyEngine != null) {
            this.skyEngine.forceHandleEmptySectionChanges(lc, empty.clone());
        }
        this.blockEngine.forceHandleEmptySectionChanges(lc, empty.clone());
        ((StarlightChunkState)chunk).starlight$setKnownEmptiness(empty);
        ((StarlightChunkState)chunk).starlight$setLightReady(true);
        this.mirrorChunk(chunk, false);
        ++this.imported;    }

    private static SWMRNibbleArray[] copyVisible(final SWMRNibbleArray[] from) {
        final SWMRNibbleArray[] ret = new SWMRNibbleArray[from.length];
        for (int i = 0; i < from.length; ++i) {
            final SWMRNibbleArray.SaveState s = from[i] == null ? null : from[i].getVisibleState();
            ret[i] = s == null ? new SWMRNibbleArray(null, true) : SWMRNibbleArray.of(s.data(), s.state());
        }
        return ret;
    }

    private long imported;

    public long importedChunks() {
        return this.imported;
    }

    // dev: -Dstarlight.checkAllEdges=true restores the full border check on every load (A/B)
    private static final boolean CHECK_ALL_EDGES = Boolean.getBoolean("starlight.checkAllEdges");

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}; // +x, -x, +z, -z (opposite = i ^ 1)

    public static long newEdgeVersion() {
        return ThreadLocalRandom.current().nextLong() | 1L; // never 0 (= unknown)
    }

    public static void bumpEdges(final Chunk chunk) {
        final long[] v = ((StarlightChunkState)chunk).starlight$edgeVersions();
        for (int i = 0; i < 4; ++i) {
            v[i] = newEdgeVersion();
        }
    }

    private StarlightChunkState readyNeighbour(final Chunk chunk, final int side) {
        final LightChunk n = this.getChunkForLighting(chunk.x + SIDES[side][0], chunk.z + SIDES[side][1]);
        return n != null && n.starlight$isLightReady() ? (StarlightChunkState)n : null;
    }

    /**
     * Called after lighting or loading a chunk and before saving it, when queued changes are applied
     */
    public void syncEdgeRecords(final Chunk chunk) {
        final StarlightChunkState self = (StarlightChunkState)chunk;
        for (int side = 0; side < 4; ++side) {
            final StarlightChunkState n = this.readyNeighbour(chunk, side);
            if (n != null) {
                self.starlight$edgeRecords()[side] = n.starlight$edgeVersions()[side ^ 1];
                n.starlight$edgeRecords()[side ^ 1] = self.starlight$edgeVersions()[side];
            }
        }
    }

    /**
     * A side needs the check if either record disagrees: equal on both sides means neither changed
     * since they were last consistent
     */
    private int edgesToCheck(final Chunk chunk) {
        final StarlightChunkState self = (StarlightChunkState)chunk;
        int mask = 0;
        for (int side = 0; side < 4; ++side) {
            final StarlightChunkState n = this.readyNeighbour(chunk, side);
            if (n == null) {
                continue; // nothing to check against; that neighbour checks when it loads
            }
            final long rec = self.starlight$edgeRecords()[side];
            if (rec == 0 || rec != n.starlight$edgeVersions()[side ^ 1]
                    || n.starlight$edgeRecords()[side ^ 1] != self.starlight$edgeVersions()[side]) {
                mask |= 1 << side;
            }
        }
        return mask;
    }

    private long[] sideVersions(final Chunk chunk) {
        final long[] v = new long[8];
        final long[] own = ((StarlightChunkState)chunk).starlight$edgeVersions();
        for (int side = 0; side < 4; ++side) {
            v[side] = own[side];
            final StarlightChunkState n = this.readyNeighbour(chunk, side);
            v[4 + side] = n == null ? 0 : n.starlight$edgeVersions()[side ^ 1];
        }
        return v;
    }

    private int changedSides(final Chunk chunk, final long[] before) {
        final long[] after = this.sideVersions(chunk);
        int mask = 0;
        for (int side = 0; side < 4; ++side) {
            if (after[4 + side] != 0 && (after[side] != before[side] || after[4 + side] != before[4 + side])) {
                mask |= 1 << side;
            }
        }
        return mask;
    }

    /**
     * Lit like a new chunk when no loaded neighbour has light from its save: only such light can
     * reflect this chunk's older blocks
     */
    public boolean canLightAsNew(final Chunk chunk) {
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
                final LightChunk n = (dx | dz) == 0 ? null : this.getChunkForLighting(chunk.x + dx, chunk.z + dz);
                if (n != null && ((StarlightChunkState)n).starlight$isLightFromSave()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** modified: new light that must be saved; saved light loaded unchanged isn't re-saved */
    public void mirrorChunk(final Chunk chunk, final boolean modified) {
        for (int y = MIN_SECTION; y <= MAX_SECTION; ++y) {
            this.mirrorSection(chunk, y);
        }
        if (modified) {
            chunk.markDirty();
        }
    }

    /** Bound, not copied: no duplicate arrays, updates publish straight into them */
    public void mirrorSection(final Chunk chunk, final int sectionY) {
        if (this.bindSection(chunk, sectionY) && this.client) {
            this.markForRender(chunk.x, sectionY, chunk.z);
        }
    }

    private boolean bindSection(final Chunk chunk, final int sectionY) {
        final ExtendedBlockStorage ebs = chunk.getBlockStorageArray()[sectionY];
        if (ebs == null) {
            return false;
        }
        final LightChunk lc = (LightChunk)chunk;
        boolean changed = lc.starlight$getBlockNibbles()[sectionY + 1].bindVisibleStorage(ebs.getBlockLight().getData());
        if (this.hasSky && ebs.getSkyLight() != null) {
            changed |= lc.starlight$getSkyNibbles()[sectionY + 1].bindVisibleStorage(ebs.getSkyLight().getData());
        }
        return changed;
    }

    private void markForRender(final int chunkX, final int sectionY, final int chunkZ) {
        final int x = chunkX << 4, y = sectionY << 4, z = chunkZ << 4;
        this.world.markBlockRangeForRenderUpdate(x, y, z, x + 15, y + 15, z + 15);
    }

    private final LongLinkedOpenHashSet clientToLight = new LongLinkedOpenHashSet();

    public void queueClientChunk(final Chunk chunk) {
        ((StarlightChunkState)chunk).starlight$setLightReady(false);
        this.clientToLight.add(ChunkPos.asLong(chunk.x, chunk.z));
    }

    public void clientFrame(final long budgetNanos) {
        this.propagateChanges();
        final long deadline = System.nanoTime() + budgetNanos;
        while (!this.clientToLight.isEmpty() && System.nanoTime() < deadline) {
            final long key = this.clientToLight.removeFirstLong();
            final Chunk chunk = (Chunk)this.getChunkForLighting((int)key, (int)(key >>> 32));
            if (chunk == null) {
                continue;
            }
            final Chunk serverChunk = ClientChunks.litServerChunk(this.world, chunk.x, chunk.z);
            if (serverChunk != null) {
                this.importLight(chunk, serverChunk);
            } else {
                this.lightChunk(chunk, false); // a re-sent chunk: neighbours may hold light from its old blocks
            }
        }
    }

    public int getBlockLight(final Chunk chunk, final int x, final int y, final int z) {
        if (y < 0 || y > 255) {
            return 0;
        }
        final SWMRNibbleArray nibble = ((LightChunk)chunk).starlight$getBlockNibbles()[(y >> 4) + 1];
        return nibble == null || nibble.isNullNibbleVisible() ? 0 : nibble.getVisible(x, y, z);
    }

    /** Sky light; in a section without data, the bottom row of the first section above that has it (15 above all). */
    public int getSkyLight(final Chunk chunk, final int x, final int y, final int z) {
        if (!this.hasSky) {
            return 0;
        }
        if (y > 255) {
            return 15;
        }
        final SWMRNibbleArray[] nibbles = ((LightChunk)chunk).starlight$getSkyNibbles();
        final int startSection = Math.max(y, -16) >> 4;
        for (int sy = startSection; sy <= MAX_LIGHT_SECTION; ++sy) {
            final SWMRNibbleArray nibble = nibbles[sy + 1];
            if (nibble != null && !nibble.isNullNibbleVisible()) {
                return nibble.getVisible(x, sy == startSection ? y : 0, z);
            }
        }
        return 15;
    }

    @Override
    public LightChunk getChunkForLighting(final int chunkX, final int chunkZ) {
        if (this.client) {
            return (LightChunk)ClientChunks.getLoaded(this.world, chunkX, chunkZ);
        }
        // loadedChunks, not getLoadedChunk: that one cancels a queued unload
        return (LightChunk)((ChunkProviderServer)this.world.getChunkProvider()).loadedChunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    @Override
    public Object getSection(final LightChunk chunk, final int sectionY) {
        return ((Chunk)chunk).getBlockStorageArray()[sectionY];
    }

    @Override
    public boolean isEmpty(final Object section) {
        return ((ExtendedBlockStorage)section).isEmpty();
    }

    @Override
    public int getOpacity(final Object section, final int localIndex, final int worldX, final int worldY, final int worldZ) {
        final IBlockState state = ((ExtendedBlockStorage)section).get(localIndex & 15, localIndex >>> 8, (localIndex >>> 4) & 15);
        return state.getLightOpacity(this.world, this.pos.setPos(worldX, worldY, worldZ));
    }

    @Override
    public int getEmission(final Object section, final int localIndex, final int worldX, final int worldY, final int worldZ) {
        final IBlockState state = ((ExtendedBlockStorage)section).get(localIndex & 15, localIndex >>> 8, (localIndex >>> 4) & 15);
        return state.getLightValue(this.world, this.pos.setPos(worldX, worldY, worldZ));
    }

    @Override
    public boolean mayHaveEmission(final Object section) {
        return Emitters.mayHaveEmitters((ExtendedBlockStorage)section);
    }

    @Override
    public boolean isClientSide() {
        return this.client;
    }

    @Override
    public boolean hasSkyLight() {
        return this.hasSky;
    }

    @Override
    public void onLightUpdate(final boolean sky, final int chunkX, final int chunkY, final int chunkZ) {
        if (chunkY < MIN_LIGHT_SECTION || chunkY > MAX_LIGHT_SECTION) {
            return;
        }
        final Chunk chunk = (Chunk)this.getChunkForLighting(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        final boolean blockSection = chunkY >= MIN_SECTION && chunkY <= MAX_SECTION;
        if (blockSection) {
            // The section's vanilla arrays are bound to Starlight's visible data, so they already hold
            // the update; bindSection only acts when they aren't bound yet (or were replaced).
            this.bindSection(chunk, chunkY);
        }
        if (this.client) {
            if (blockSection) {
                this.markForRender(chunkX, chunkY, chunkZ);
            }
        } else {
            // sections -1 and 16 too: their light is saved (explicitly) and reaches the borders
            // save only real light changes: state-only changes are recomputed on every load
            final SWMRNibbleArray nibble = (sky ? ((LightChunk)chunk).starlight$getSkyNibbles() : ((LightChunk)chunk).starlight$getBlockNibbles())[chunkY + 1];
            if (nibble != null && nibble.lastUpdateChangedData()) {
                chunk.markDirty();
                bumpEdges(chunk);
            }
        }
    }

    public String memStats() {
        long chunks = 0, priv = 0, bound = 0, full = 0;
        for (final Chunk ch : ((ChunkProviderServer)this.world.getChunkProvider()).loadedChunks.values()) {
            ++chunks;
            final LightChunk lc = (LightChunk)ch;
            for (final SWMRNibbleArray[] layer : new SWMRNibbleArray[][] {lc.starlight$getBlockNibbles(), lc.starlight$getSkyNibbles()}) {
                for (final SWMRNibbleArray n : layer) {
                    if (n != null) {
                        final int[] k = n.storageKinds();
                        priv += k[0];
                        bound += k[1];
                        full += k[2];
                    }
                }
            }
        }
        return String.format("chunks %d | light arrays: private %d (%.1f MB), bound to vanilla %d, shared all-15 %d | work pool %d",
                chunks, priv, priv * SWMRNibbleArray.ARRAY_SIZE / 1048576.0, bound, full, SWMRNibbleArray.poolSize());
    }
    public static void flush(final World world) {
        final WorldLight light = world == null ? null : ((StarlightWorld)world).starlight$getLight();
        if (light != null) {
            light.propagateChanges();
        }
    }

    public interface StarlightChunkState {
        void starlight$setLightReady(boolean ready);

        /**
         * Per side (+x, -x, +z, -z): a random version, replaced whenever the chunk's light changes,
         * and the neighbour's version this chunk was last consistent with (0 = unknown).
         */
        long[] starlight$edgeVersions();

        long[] starlight$edgeRecords();

        /** Loaded from a save without edge versions: save it once with them. */
        boolean starlight$needsEdgeUpgrade();

        void starlight$setEdgeUpgrade(boolean upgrade);

        /** Whether the chunk's light came from its save (computed in an earlier session, possibly with neighbours' older blocks). */
        boolean starlight$isLightFromSave();

        void starlight$setLightFromSave(boolean fromSave);

        /** Section emptiness Starlight has registered (set when lit or loaded; later changes are queued against it). */
        void starlight$setKnownEmptiness(Boolean[] empty);

        /** Set when the chunk was read with valid saved Starlight light (consumed by onLoad). */
        void starlight$setSavedLight(boolean saved);

        boolean starlight$hasSavedLight();
    }
}
