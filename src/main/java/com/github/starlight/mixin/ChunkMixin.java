package com.github.starlight.mixin;

import com.github.starlight.light.LightChunk;
import com.github.starlight.light.SWMRNibbleArray;
import com.github.starlight.light.StarLightEngine;
import com.github.starlight.world.StarlightWorld;
import com.github.starlight.world.WorldLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Starlight's per-chunk light state, and on server worlds the replacement of vanilla's light
 * work: heightmaps are still maintained (rain, canSeeSky, spawning use them), light comes from
 * Starlight (queued block changes, chunk lighting after population on the server, on arrival on
 * the client).
 */
@Mixin(Chunk.class)
public abstract class ChunkMixin implements LightChunk, WorldLight.StarlightChunkState {

    @Shadow @Final public int x;
    @Shadow @Final public int z;
    @Shadow @Final private World world;
    @Shadow @Final private ExtendedBlockStorage[] storageArrays;
    @Shadow @Final private int[] heightMap;
    @Shadow @Final private int[] precipitationHeightMap;
    @Shadow private int heightMapMinimum;
    @Shadow private boolean dirty;
    @Shadow private boolean isTerrainPopulated;
    @Shadow private boolean isLightPopulated;

    @Shadow public abstract int getTopFilledSegment();
    @Shadow
    private int getBlockLightOpacity(int x, int y, int z) {
        throw new AssertionError();
    }

    @Unique private SWMRNibbleArray[] starlight$blockNibbles = StarLightEngine.getFilledEmptyLight();
    @Unique private SWMRNibbleArray[] starlight$skyNibbles = StarLightEngine.getFilledEmptyLight();
    @Unique private boolean[] starlight$blockEmptiness;
    @Unique private boolean[] starlight$skyEmptiness;
    @Unique private volatile boolean starlight$lightReady;

    // ── LightChunk ──

    @Override public int starlight$chunkX() { return this.x; }
    @Override public int starlight$chunkZ() { return this.z; }
    @Override public boolean starlight$isLightReady() { return this.starlight$lightReady; }
    @Override public void starlight$setLightReady(final boolean ready) { this.starlight$lightReady = ready; }
    @Unique private boolean starlight$savedLight;
    @Unique private boolean starlight$lightFromSave;
    @Unique private final long[] starlight$edgeVersions = {WorldLight.newEdgeVersion(), WorldLight.newEdgeVersion(), WorldLight.newEdgeVersion(), WorldLight.newEdgeVersion()};
    @Unique private final long[] starlight$edgeRecords = new long[4];
    @Override public long[] starlight$edgeVersions() { return this.starlight$edgeVersions; }
    @Override public long[] starlight$edgeRecords() { return this.starlight$edgeRecords; }
    @Unique private boolean starlight$edgeUpgrade;
    @Override public boolean starlight$needsEdgeUpgrade() { return this.starlight$edgeUpgrade; }
    @Override public void starlight$setEdgeUpgrade(final boolean upgrade) { this.starlight$edgeUpgrade = upgrade; }
    @Override public boolean starlight$isLightFromSave() { return this.starlight$lightFromSave; }
    @Override public void starlight$setLightFromSave(final boolean fromSave) { this.starlight$lightFromSave = fromSave; }
    @Override public void starlight$setSavedLight(final boolean saved) { this.starlight$savedLight = saved; }
    @Override public boolean starlight$hasSavedLight() { return this.starlight$savedLight; }
    @Override public SWMRNibbleArray[] starlight$getBlockNibbles() { return this.starlight$blockNibbles; }
    @Override public void starlight$setBlockNibbles(final SWMRNibbleArray[] nibbles) { this.starlight$blockNibbles = nibbles; }
    @Override public SWMRNibbleArray[] starlight$getSkyNibbles() { return this.starlight$skyNibbles; }
    @Override public void starlight$setSkyNibbles(final SWMRNibbleArray[] nibbles) { this.starlight$skyNibbles = nibbles; }
    @Override public boolean[] starlight$getBlockEmptinessMap() { return this.starlight$blockEmptiness; }
    @Override public void starlight$setBlockEmptinessMap(final boolean[] map) { this.starlight$blockEmptiness = map; }
    @Override public boolean[] starlight$getSkyEmptinessMap() { return this.starlight$skyEmptiness; }
    @Override public void starlight$setSkyEmptinessMap(final boolean[] map) { this.starlight$skyEmptiness = map; }

    @Unique
    private WorldLight starlight$light() {
        return this.world == null ? null : ((StarlightWorld)this.world).starlight$getLight();
    }

    // ── Vanilla light work replaced on server worlds ──

    /** Generation/section creation: keep the heightmap part, drop the sky light writes. */
    @Inject(method = "generateSkylightMap", at = @At("HEAD"), cancellable = true)
    private void starlight$heightMapOnly(final CallbackInfo ci) {
        if (this.starlight$light() == null) {
            return;
        }
        final int top = this.getTopFilledSegment();
        this.heightMapMinimum = Integer.MAX_VALUE;
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                this.precipitationHeightMap[x + (z << 4)] = -999;
                for (int y = top + 16; y > 0; --y) {
                    if (this.getBlockLightOpacity(x, y - 1, z) != 0) {
                        this.heightMap[z << 4 | x] = y;
                        if (y < this.heightMapMinimum) {
                            this.heightMapMinimum = y;
                        }
                        break;
                    }
                }
            }
        }
        this.dirty = true;
        ci.cancel();
    }

    /** Block change below/at the height: keep the heightmap update, Starlight handles the light. */
    @Inject(method = "relightBlock", at = @At("HEAD"), cancellable = true)
    private void starlight$relightHeightOnly(final int x, final int y, final int z, final CallbackInfo ci) {
        if (this.starlight$light() == null) {
            return;
        }
        final int old = this.heightMap[z << 4 | x] & 255;
        int height = Math.max(old, y);
        while (height > 0 && this.getBlockLightOpacity(x, height - 1, z) == 0) {
            --height;
        }
        if (height != old) {
            this.world.markBlocksDirtyVertical(x + this.x * 16, z + this.z * 16, height, old);
            this.heightMap[z << 4 | x] = height;
            if (height < this.heightMapMinimum) {
                this.heightMapMinimum = height;
            }
            this.dirty = true;
        }
        ci.cancel();
    }

    @Inject(method = "propagateSkylightOcclusion", at = @At("HEAD"), cancellable = true)
    private void starlight$noSkylightOcclusion(final int x, final int z, final CallbackInfo ci) {
        if (this.starlight$light() != null) {
            ci.cancel();
        }
    }

    @Inject(method = "recheckGaps", at = @At("HEAD"), cancellable = true)
    private void starlight$noGapChecks(final boolean onlyOne, final CallbackInfo ci) {
        if (this.starlight$light() != null) {
            ci.cancel();
        }
    }

    @Inject(method = "enqueueRelightChecks", at = @At("HEAD"), cancellable = true)
    private void starlight$noRelightChecks(final CallbackInfo ci) {
        if (this.starlight$light() != null) {
            ci.cancel();
        }
    }

    /** Vanilla's deferred light pass: Starlight lights the chunk itself; only the flags remain. */
    @Inject(method = "checkLight()V", at = @At("HEAD"), cancellable = true)
    private void starlight$lightFlagsOnly(final CallbackInfo ci) {
        if (this.starlight$light() != null) {
            this.isTerrainPopulated = true;
            this.isLightPopulated = true;
            ci.cancel();
        }
    }

    // ── Chunk lighting: loaded populated chunks, and chunks that just finished population ──

    @Inject(method = "onLoad", at = @At("TAIL"))
    private void starlight$lightOnLoad(final CallbackInfo ci) {
        final WorldLight light = this.starlight$light();
        if (light == null || this.world.isRemote) {
            return; // client chunks are lit when their data arrives (ChunkClientMixin)
        }
        if (this.starlight$savedLight) {
            this.starlight$savedLight = false;
            light.loadSavedLight((Chunk)(Object)this);
        } else if (this.isTerrainPopulated) {
            // saved without Starlight light: full edge checks only if a neighbour's saved light may disagree
            light.lightChunk((Chunk)(Object)this, light.canLightAsNew((Chunk)(Object)this));
        }
    }

    @Unique private boolean starlight$wasPopulated;

    @Inject(method = "populate(Lnet/minecraft/world/gen/IChunkGenerator;)V", at = @At("HEAD"))
    private void starlight$populateHead(final IChunkGenerator generator, final CallbackInfo ci) {
        this.starlight$wasPopulated = this.isTerrainPopulated;
    }

    @Inject(method = "populate(Lnet/minecraft/world/gen/IChunkGenerator;)V", at = @At("TAIL"))
    private void starlight$lightAfterPopulate(final IChunkGenerator generator, final CallbackInfo ci) {
        final WorldLight light = this.starlight$light();
        if (light != null && !this.world.isRemote && !this.starlight$wasPopulated) {
            light.lightChunk((Chunk)(Object)this, true); // just generated
        }
    }

    // ── Section emptiness changes and new sections ──

    // Emptiness of each block section as Starlight last saw it (set when the chunk is lit or
    // loaded, updated as changes are queued). Compared at every setBlockState return, so nested
    // setBlockState calls on the same chunk (breakBlock/onBlockAdded callbacks) and exceptions
    // can't lose a change, as per-call state captured at HEAD would.
    @Unique private boolean[] starlight$knownEmpty;

    @Override
    public void starlight$setKnownEmptiness(final Boolean[] empty) {
        final boolean[] known = new boolean[empty.length];
        for (int i = 0; i < known.length; ++i) {
            known[i] = empty[i];
        }
        this.starlight$knownEmpty = known;
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void starlight$sectionAfter(final BlockPos pos, final IBlockState state, final CallbackInfoReturnable<IBlockState> cir) {
        final int sy = pos.getY() >> 4;
        final boolean[] known = this.starlight$knownEmpty;
        if (sy < 0 || sy > 15 || !this.starlight$lightReady || known == null) {
            return; // not lit yet: lighting reads the current sections
        }
        final WorldLight light = this.starlight$light();
        if (light == null) {
            return;
        }
        final ExtendedBlockStorage ebs = this.storageArrays[sy];
        if (ebs != null) {
            light.mirrorSection((Chunk)(Object)this, sy); // binds a newly created section's arrays to Starlight's light
        }
        final boolean empty = ebs == null || ebs.isEmpty();
        if (known[sy] != empty) {
            known[sy] = empty;
            light.queueSectionChange(this.x, sy, this.z, empty);
        }
    }
    // ── Reads come from Starlight once the chunk is lit ──

    /**
     * Unlit server chunks (not populated yet): vanilla's rule for sections without data, sky 15
     * where the column sees the sky. Their vanilla arrays hold zeros, since the vanilla sky fill
     * at generation is skipped. Client chunks fall back to the packet's light instead.
     */
    @Unique
    private int starlight$unlitSkyLight(final BlockPos pos) {
        return this.world.provider.hasSkyLight() && this.canSeeSky(pos) ? 15 : 0;
    }

    @Shadow public abstract boolean canSeeSky(BlockPos pos);

    @Inject(method = "getLightFor", at = @At("HEAD"), cancellable = true)
    private void starlight$getLightFor(final EnumSkyBlock type, final BlockPos pos, final CallbackInfoReturnable<Integer> cir) {
        if (!this.starlight$lightReady) {
            if (this.world != null && !this.world.isRemote && this.starlight$light() != null) {
                cir.setReturnValue(type == EnumSkyBlock.SKY ? this.starlight$unlitSkyLight(pos) : 0);
            }
            return;
        }
        final WorldLight light = this.starlight$light();
        if (light != null) {
            final Chunk self = (Chunk)(Object)this;
            cir.setReturnValue(type == EnumSkyBlock.SKY
                    ? light.getSkyLight(self, pos.getX(), pos.getY(), pos.getZ())
                    : light.getBlockLight(self, pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    @Inject(method = "getLightSubtracted", at = @At("HEAD"), cancellable = true)
    private void starlight$getLightSubtracted(final BlockPos pos, final int amount, final CallbackInfoReturnable<Integer> cir) {
        if (!this.starlight$lightReady) {
            if (this.world != null && !this.world.isRemote && this.starlight$light() != null) {
                cir.setReturnValue(Math.max(0, this.starlight$unlitSkyLight(pos) - amount));
            }
            return;
        }
        final WorldLight light = this.starlight$light();
        if (light != null) {
            final Chunk self = (Chunk)(Object)this;
            final int sky = light.getSkyLight(self, pos.getX(), pos.getY(), pos.getZ()) - amount;
            final int block = light.getBlockLight(self, pos.getX(), pos.getY(), pos.getZ());
            cir.setReturnValue(Math.max(sky, block));
        }
    }

    /** Direct light writes (mods, commands): also into Starlight's data, without propagation. */
    @Inject(method = "setLightFor", at = @At("TAIL"))
    private void starlight$setLightFor(final EnumSkyBlock type, final BlockPos pos, final int value, final CallbackInfo ci) {
        if (!this.starlight$lightReady || this.starlight$light() == null || pos.getY() < 0 || pos.getY() > 255) {
            return;
        }
        final SWMRNibbleArray nibble = (type == EnumSkyBlock.SKY ? this.starlight$skyNibbles : this.starlight$blockNibbles)[(pos.getY() >> 4) + 1];
        if (nibble != null && !nibble.isNullNibbleUpdating()) {
            nibble.set(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, value);
            nibble.updateVisible();
            if (!this.world.isRemote) {
                WorldLight.bumpEdges((Chunk)(Object)this); // light changed outside the engine
            }
        }
        this.starlight$light().mirrorSection((Chunk)(Object)this, pos.getY() >> 4); // setLightFor may have created the section
    }
}
