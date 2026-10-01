package com.github.starlight.mixin;

import com.github.starlight.world.LightSave;
import com.github.starlight.world.WorldLight;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk saves carry current light (flush point) plus Starlight's light data; loads restore it
 * (may run on Forge's chunk IO thread: it only fills the not-yet-published chunk).
 */
@Mixin(AnvilChunkLoader.class)
public abstract class AnvilChunkLoaderMixin {

    @Inject(method = "saveChunk", at = @At("HEAD"))
    private void starlight$flushBeforeChunkSave(final World world, final Chunk chunk, final CallbackInfo ci) {
        WorldLight.flush(world);
    }

    @Inject(method = "writeChunkToNBT", at = @At("RETURN"))
    private void starlight$writeLight(final Chunk chunk, final World world, final NBTTagCompound level, final CallbackInfo ci) {
        if (!world.isRemote) {
            final WorldLight light = ((com.github.starlight.world.StarlightWorld)world).starlight$getLight();
            if (light != null && ((com.github.starlight.light.LightChunk)chunk).starlight$isLightReady()) {
                light.syncEdgeRecords(chunk); // saveChunk applied queued changes first: consistent now
            }
            LightSave.write(chunk, world.provider.hasSkyLight(), level);
        }
    }

    @Inject(method = "readChunkFromNBT", at = @At("RETURN"))
    private void starlight$readLight(final World world, final NBTTagCompound level, final CallbackInfoReturnable<Chunk> cir) {
        final Chunk chunk = cir.getReturnValue();
        if (chunk != null && !world.isRemote && LightSave.read(chunk, world.provider.hasSkyLight(), level)) {
            ((WorldLight.StarlightChunkState)chunk).starlight$setSavedLight(true);
        }
    }
}