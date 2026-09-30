package com.github.starlight.mixin;

import com.github.starlight.world.WorldLight;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flush point: every single-chunk save (unloads included) writes current light. */
@Mixin(AnvilChunkLoader.class)
public abstract class AnvilChunkLoaderMixin {

    @Inject(method = "saveChunk", at = @At("HEAD"))
    private void starlight$flushBeforeChunkSave(final World world, final Chunk chunk, final CallbackInfo ci) {
        WorldLight.flush(world);
    }
}