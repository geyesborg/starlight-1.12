package com.github.starlight.mixin;

import com.github.starlight.light.LightChunk;
import com.github.starlight.world.LightSave;
import com.github.starlight.world.StarlightWorld;
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
 * Saves carry current light plus Starlight's data; loads may run on Forge's chunk IO thread (the
 * chunk isn't published yet)
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
            final WorldLight light = ((StarlightWorld)world).starlight$getLight();
            if (light != null && ((LightChunk)chunk).starlight$isLightReady()) {
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