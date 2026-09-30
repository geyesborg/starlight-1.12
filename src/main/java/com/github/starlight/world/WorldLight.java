package com.github.starlight.world;

import com.github.starlight.light.BlockStarLightEngine;
import com.github.starlight.light.LightChunk;
import com.github.starlight.light.LightWorld;
import com.github.starlight.light.SWMRNibbleArray;
import com.github.starlight.light.SkyStarLightEngine;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Starlight for one world (Moonrise's StarLightInterface, 1.12): the engines, the change queue
 * fed by World.checkLightFor and Chunk.setBlockState, light readers, and the mirror of
 * Starlight's arrays into the vanilla ExtendedBlockStorage arrays (saves, packets, mods and
 * Celeritas' chunk meshing read those). One thread per world (server thread, or the client
 * thread for the client world); the queue tolerates calls from other threads.
 *
 * <p>Client worlds light arriving chunks within a per-frame time budget (until then reads fall
 * back to the packet's light), and re-render a section only when its mirrored light changed.</p>
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

    // ── Queue (called where vanilla would update light immediately) ──

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

    // ── Chunk lifecycle ──

    /** Light a populated chunk from scratch; it becomes readable (and propagated into) afterwards. */
    public void lightChunk(final Chunk chunk) {
        final LightChunk lc = (LightChunk)chunk;
        if (lc.starlight$isLightReady()) {
            return;
        }
        final long start = System.nanoTime();
        final Boolean[] empty = this.blockEngine.getEmptySectionsForChunk(lc);
        if (this.skyEngine != null) {
            this.skyEngine.light(lc, empty.clone());
        }
        this.blockEngine.light(lc, empty.clone());
        ((StarlightChunkState)chunk).starlight$setLightReady(true);
        this.mirrorChunk(chunk);
        LightStats.chunkLit(System.nanoTime() - start);
    }

    /**
     * A chunk loaded with saved Starlight light: register its section emptiness (initialising
     * neighbour data where needed), then check its edges against loaded neighbours, which may
     * have changed while it was unloaded.
     */
    public void loadSavedLight(final Chunk chunk) {
        final LightChunk lc = (LightChunk)chunk;
        final long start = System.nanoTime();
        final Boolean[] empty = this.blockEngine.getEmptySectionsForChunk(lc);
        if (this.skyEngine != null) {
            this.skyEngine.forceHandleEmptySectionChanges(lc, empty.clone());
        }
        this.blockEngine.forceHandleEmptySectionChanges(lc, empty.clone());
        ((StarlightChunkState)chunk).starlight$setLightReady(true);
        if (this.skyEngine != null) {
            this.skyEngine.checkChunkEdges(chunk.x, chunk.z);
        }
        this.blockEngine.checkChunkEdges(chunk.x, chunk.z);
        this.mirrorChunk(chunk);
        LightStats.chunkLoaded(System.nanoTime() - start);
    }

    /** Copy all of a chunk's light into its vanilla section arrays. */
    public void mirrorChunk(final Chunk chunk) {
        for (int y = MIN_SECTION; y <= MAX_SECTION; ++y) {
            this.mirrorSection(chunk, y);
        }
        chunk.markDirty();
    }

    /**
     * Copy one section's visible light into its vanilla arrays (no-op without an
     * ExtendedBlockStorage); on the client, a section whose light changed is re-rendered.
     */
    public void mirrorSection(final Chunk chunk, final int sectionY) {
        final ExtendedBlockStorage ebs = chunk.getBlockStorageArray()[sectionY];
        if (ebs == null) {
            return;
        }
        final LightChunk lc = (LightChunk)chunk;
        boolean changed = lc.starlight$getBlockNibbles()[sectionY + 1].copyVisibleIntoIfChanged(ebs.getBlockLight().getData());
        if (this.hasSky && ebs.getSkyLight() != null) {
            changed |= lc.starlight$getSkyNibbles()[sectionY + 1].copyVisibleIntoIfChanged(ebs.getSkyLight().getData());
        }
        if (changed && this.client) {
            this.markForRender(chunk.x, sectionY, chunk.z);
        }
    }

    private void markForRender(final int chunkX, final int sectionY, final int chunkZ) {
        final int x = chunkX << 4, y = sectionY << 4, z = chunkZ << 4;
        this.world.markBlockRangeForRenderUpdate(x, y, z, x + 15, y + 15, z + 15);
    }

    // ── Client: arriving chunks are lit within a per-frame budget ──

    private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet clientToLight = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();

    /** A chunk's blocks arrived (or were replaced) from the server: light it again. */
    public void queueClientChunk(final Chunk chunk) {
        ((StarlightChunkState)chunk).starlight$setLightReady(false);
        this.clientToLight.add(ChunkPos.asLong(chunk.x, chunk.z));
    }

    /** Client, once per frame: apply queued block changes, then light arriving chunks for up to {@code budgetNanos}. */
    public void clientFrame(final long budgetNanos) {
        this.propagateChanges();
        final long deadline = System.nanoTime() + budgetNanos;
        while (!this.clientToLight.isEmpty() && System.nanoTime() < deadline) {
            final long key = this.clientToLight.removeFirstLong();
            final Chunk chunk = (Chunk)this.getChunkForLighting((int)key, (int)(key >>> 32));
            if (chunk != null) {
                this.lightChunk(chunk);
            }
        }
    }

    // ── Readers (visible data) ──

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

    // ── LightWorld ──

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
    public boolean isClientSide() {
        return this.client;
    }

    @Override
    public boolean hasSkyLight() {
        return this.hasSky;
    }

    @Override
    public void onLightUpdate(final boolean sky, final int chunkX, final int chunkY, final int chunkZ) {
        if (chunkY < MIN_SECTION || chunkY > MAX_SECTION) {
            return;
        }
        final Chunk chunk = (Chunk)this.getChunkForLighting(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        final ExtendedBlockStorage ebs = chunk.getBlockStorageArray()[chunkY];
        if (ebs == null) {
            return;
        }
        final LightChunk lc = (LightChunk)chunk;
        final boolean changed;
        if (sky) {
            changed = ebs.getSkyLight() != null && lc.starlight$getSkyNibbles()[chunkY + 1].copyVisibleIntoIfChanged(ebs.getSkyLight().getData());
        } else {
            changed = lc.starlight$getBlockNibbles()[chunkY + 1].copyVisibleIntoIfChanged(ebs.getBlockLight().getData());
        }
        if (!changed) {
            return;
        }
        if (this.client) {
            this.markForRender(chunkX, chunkY, chunkZ);
        } else {
            chunk.markDirty();
        }
    }

    /** Apply the world's queued light changes if it has Starlight (flush points: saves, chunk packets). */
    public static void flush(final net.minecraft.world.World world) {
        final WorldLight light = world == null ? null : ((StarlightWorld)world).starlight$getLight();
        if (light != null) {
            light.propagateChanges();
        }
    }

    /** Starlight state for chunks the port keeps on 1.12 Chunk (mixin). */
    public interface StarlightChunkState {
        void starlight$setLightReady(boolean ready);

        /** Set when the chunk was read with valid saved Starlight light (consumed by onLoad). */
        void starlight$setSavedLight(boolean saved);

        boolean starlight$hasSavedLight();
    }
}
