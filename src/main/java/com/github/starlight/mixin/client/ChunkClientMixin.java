package com.github.starlight.mixin.client;

import com.github.starlight.world.StarlightWorld;
import com.github.starlight.world.WorldLight;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk data from the server: the client relights the chunk within its frame budget, reading the
 * packet's light until then
 */
@Mixin(Chunk.class)
public abstract class ChunkClientMixin {

    @Shadow @Final private World world;

    @Inject(method = "read", at = @At("TAIL"))
    private void starlight$lightReceivedChunk(final PacketBuffer buf, final int availableSections, final boolean groundUpContinuous,
                                              final CallbackInfo ci) {
        final WorldLight light = ((StarlightWorld)this.world).starlight$getLight();
        if (light != null) {
            light.queueClientChunk((Chunk)(Object)this);
        }
    }
}