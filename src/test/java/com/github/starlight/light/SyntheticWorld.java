package com.github.starlight.light;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;

final class SyntheticWorld implements LightWorld {

    // id: air, stone, glass, water, leaves, glowstone, torch, lava, ice
    static final int AIR = 0, STONE = 1, GLASS = 2, WATER = 3, LEAVES = 4, GLOWSTONE = 5, TORCH = 6, LAVA = 7, ICE = 8;
    static final int[] OPACITY = {0, 255, 0, 3, 1, 255, 0, 255, 3};
    static final int[] EMISSION = {0, 0, 0, 0, 0, 15, 14, 15, 0};

    static final class Section {
        final byte[] blocks = new byte[4096];
        int nonAir;
    }

    static final class Chunk implements LightChunk {
        final int x, z;
        final Section[] sections = new Section[TOTAL_SECTIONS];
        boolean lightReady;
        SWMRNibbleArray[] blockNibbles = StarLightEngine.getFilledEmptyLight();
        SWMRNibbleArray[] skyNibbles = StarLightEngine.getFilledEmptyLight();
        boolean[] blockEmptiness, skyEmptiness;

        Chunk(final int x, final int z) {
            this.x = x;
            this.z = z;
        }

        @Override public int starlight$chunkX() { return this.x; }
        @Override public int starlight$chunkZ() { return this.z; }
        @Override public boolean starlight$isLightReady() { return this.lightReady; }
        @Override public SWMRNibbleArray[] starlight$getBlockNibbles() { return this.blockNibbles; }
        @Override public void starlight$setBlockNibbles(final SWMRNibbleArray[] nibbles) { this.blockNibbles = nibbles; }
        @Override public SWMRNibbleArray[] starlight$getSkyNibbles() { return this.skyNibbles; }
        @Override public void starlight$setSkyNibbles(final SWMRNibbleArray[] nibbles) { this.skyNibbles = nibbles; }
        @Override public boolean[] starlight$getBlockEmptinessMap() { return this.blockEmptiness; }
        @Override public void starlight$setBlockEmptinessMap(final boolean[] map) { this.blockEmptiness = map; }
        @Override public boolean[] starlight$getSkyEmptinessMap() { return this.skyEmptiness; }
        @Override public void starlight$setSkyEmptinessMap(final boolean[] map) { this.skyEmptiness = map; }
    }

    final Long2ObjectOpenHashMap<Chunk> chunks = new Long2ObjectOpenHashMap<>();
    final BlockStarLightEngine blockEngine = new BlockStarLightEngine(this);
    final SkyStarLightEngine skyEngine = new SkyStarLightEngine(this);
    int lightUpdates;

    static long key(final int x, final int z) {
        return ((long)z << 32) | (x & 0xFFFFFFFFL);
    }

    Chunk addChunk(final int x, final int z) {
        final Chunk c = new Chunk(x, z);
        this.chunks.put(key(x, z), c);
        return c;
    }

    int getBlock(final int x, final int y, final int z) {
        final Chunk c = this.chunks.get(key(x >> 4, z >> 4));
        if (c == null || y < 0 || y > 255) {
            return AIR;
        }
        final Section s = c.sections[y >> 4];
        return s == null ? AIR : s.blocks[(x & 15) | ((z & 15) << 4) | ((y & 15) << 8)];
    }

    void setBlockRaw(final int x, final int y, final int z, final int id) {
        final Chunk c = this.chunks.get(key(x >> 4, z >> 4));
        Section s = c.sections[y >> 4];
        if (s == null) {
            if (id == AIR) {
                return;
            }
            s = c.sections[y >> 4] = new Section();
        }
        final int idx = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
        final int old = s.blocks[idx];
        s.nonAir += (id != AIR ? 1 : 0) - (old != AIR ? 1 : 0);
        s.blocks[idx] = (byte)id;
    }

    private final Long2ObjectLinkedOpenHashMap<IntArrayList> pendingPositions = new Long2ObjectLinkedOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Boolean[]> pendingSections = new Long2ObjectOpenHashMap<>();

    void setBlock(final int x, final int y, final int z, final int id) {
        final Chunk c = this.chunks.get(key(x >> 4, z >> 4));
        final Section before = c.sections[y >> 4];
        final boolean wasEmpty = before == null || before.nonAir == 0;
        this.setBlockRaw(x, y, z, id);
        final Section after = c.sections[y >> 4];
        final boolean isEmpty = after == null || after.nonAir == 0;
        final long k = key(x >> 4, z >> 4);
        this.pendingPositions.computeIfAbsent(k, kk -> new IntArrayList()).addAll(IntArrayList.of(x, y, z));
        if (wasEmpty != isEmpty) {
            this.pendingSections.computeIfAbsent(k, kk -> new Boolean[TOTAL_SECTIONS])[y >> 4] = isEmpty;
        }
    }

    void flush() {
        for (final var e : this.pendingPositions.long2ObjectEntrySet()) {
            final long k = e.getLongKey();
            final IntArrayList pos = e.getValue();
            final Boolean[] sections = this.pendingSections.remove(k);
            final int cx = (int)k, cz = (int)(k >>> 32);
            this.skyEngine.blocksChangedInChunk(cx, cz, pos.elements(), pos.size() / 3, sections == null ? null : sections.clone());
            this.blockEngine.blocksChangedInChunk(cx, cz, pos.elements(), pos.size() / 3, sections == null ? null : sections.clone());
        }
        this.pendingPositions.clear();
        this.pendingSections.clear();
    }

    void lightChunk(final Chunk c, final boolean fresh) {
        final Boolean[] empty = this.blockEngine.getEmptySectionsForChunk(c);
        this.skyEngine.light(c, empty.clone(), fresh);
        this.blockEngine.light(c, empty.clone(), fresh);
        c.lightReady = true;
    }

    @Override
    public LightChunk getChunkForLighting(final int chunkX, final int chunkZ) {
        return this.chunks.get(key(chunkX, chunkZ));
    }

    @Override
    public Object getSection(final LightChunk chunk, final int sectionY) {
        return ((Chunk)chunk).sections[sectionY];
    }

    @Override
    public boolean isEmpty(final Object section) {
        return ((Section)section).nonAir == 0;
    }

    @Override
    public int getOpacity(final Object section, final int localIndex, final int worldX, final int worldY, final int worldZ) {
        return OPACITY[((Section)section).blocks[localIndex]];
    }

    @Override
    public int getEmission(final Object section, final int localIndex, final int worldX, final int worldY, final int worldZ) {
        return EMISSION[((Section)section).blocks[localIndex]];
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public boolean hasSkyLight() {
        return true;
    }

    @Override
    public void onLightUpdate(final boolean sky, final int chunkX, final int chunkY, final int chunkZ) {
        ++this.lightUpdates;
    }

    int getBlockLight(final int x, final int y, final int z) {
        final Chunk c = this.chunks.get(key(x >> 4, z >> 4));
        final SWMRNibbleArray n = c.blockNibbles[(y >> 4) + 1];
        return n == null || n.isNullNibbleVisible() ? 0 : n.getVisible(x, y, z);
    }

    /** Sky light: a null section reads the bottom row of the first initialised section above (15 above all). */
    int getSkyLight(final int x, final int y, final int z) {
        final Chunk c = this.chunks.get(key(x >> 4, z >> 4));
        for (int sy = y >> 4; sy <= MAX_LIGHT_SECTION; ++sy) {
            final SWMRNibbleArray n = c.skyNibbles[sy + 1];
            if (n != null && !n.isNullNibbleVisible()) {
                return n.getVisible(x, sy == (y >> 4) ? y : 0, z);
            }
        }
        return 15;
    }

    // Reference: exact light fixpoint over the loaded chunks, y -16..271

    static final int MIN_Y = MIN_LIGHT_SECTION << 4, MAX_Y = (MAX_LIGHT_SECTION << 4) | 15, HEIGHT = MAX_Y - MIN_Y + 1;

    record Area(int minCX, int minCZ, int sizeX, int sizeZ) {
        int index(final int x, final int y, final int z) {
            return ((x - (this.minCX << 4)) * (this.sizeZ << 4) + (z - (this.minCZ << 4))) * HEIGHT + (y - MIN_Y);
        }
    }

    Area area() {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final Chunk c : this.chunks.values()) {
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
        }
        return new Area(minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
    }

    boolean loaded(final int x, final int z) {
        return this.chunks.containsKey(key(x >> 4, z >> 4));
    }

    /** Max-propagation with a bucket queue: light = max(source, neighbour - max(1, opacity)). */
    byte[] referenceLight(final Area a, final boolean sky) {
        final byte[] level = new byte[(a.sizeX() << 4) * (a.sizeZ() << 4) * HEIGHT];
        final IntArrayList[] buckets = new IntArrayList[16];
        Arrays.setAll(buckets, i -> new IntArrayList());
        final int x0 = a.minCX() << 4, z0 = a.minCZ() << 4, x1 = x0 + (a.sizeX() << 4) - 1, z1 = z0 + (a.sizeZ() << 4) - 1;
        for (int x = x0; x <= x1; ++x) {
            for (int z = z0; z <= z1; ++z) {
                if (!this.loaded(x, z)) {
                    continue;
                }
                if (sky) {
                    for (int y = MAX_Y; y >= MIN_Y && OPACITY[this.getBlock(x, y, z)] == 0; --y) {
                        level[a.index(x, y, z)] = 15;
                        buckets[15].add(a.index(x, y, z));
                    }
                } else {
                    for (int y = 0; y <= 255; ++y) {
                        final int e = EMISSION[this.getBlock(x, y, z)];
                        if (e > 0) {
                            level[a.index(x, y, z)] = (byte)e;
                            buckets[e].add(a.index(x, y, z));
                        }
                    }
                }
            }
        }
        final int strideZ = HEIGHT, strideX = (a.sizeZ() << 4) * HEIGHT;
        for (int l = 15; l >= 2; --l) {
            final IntArrayList b = buckets[l];
            for (int i = 0; i < b.size(); ++i) {
                final int idx = b.getInt(i);
                if (level[idx] != l) {
                    continue;
                }
                final int y = idx % HEIGHT + MIN_Y;
                final int zi = (idx / strideZ) % (a.sizeZ() << 4);
                final int xi = idx / strideX;
                final int x = x0 + xi, z = z0 + zi;
                for (int d = 0; d < 6; ++d) {
                    final int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    final int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    final int nz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
                    if (nx < x0 || nx > x1 || nz < z0 || nz > z1 || ny < MIN_Y || ny > MAX_Y || !this.loaded(nx, nz)) {
                        continue;
                    }
                    final int t = l - Math.max(1, OPACITY[this.getBlock(nx, ny, nz)]);
                    final int nIdx = a.index(nx, ny, nz);
                    if (t > level[nIdx]) {
                        level[nIdx] = (byte)t;
                        buckets[t].add(nIdx);
                    }
                }
            }
        }
        return level;
    }
}
