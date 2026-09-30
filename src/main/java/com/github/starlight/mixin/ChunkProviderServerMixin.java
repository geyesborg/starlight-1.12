package com.github.starlight.mixin;

import com.github.starlight.world.WorldLight;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Flush point: queued light changes are applied before chunks are saved (as Alfheim does). */
@Mixin(ChunkProviderServer.class)
public abstract class ChunkProviderServerMixin {

    @Shadow @Final private WorldServer world;

    @Inject(method = "saveChunks", at = @At("HEAD"))
    private void starlight$flushBeforeSave(final boolean all, final CallbackInfoReturnable<Boolean> cir) {
        WorldLight.flush(this.world);
    }
}