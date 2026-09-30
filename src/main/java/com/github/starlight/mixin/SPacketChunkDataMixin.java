package com.github.starlight.mixin;

import com.github.starlight.world.WorldLight;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Flush point: chunk packets carry current light (the constructor's first world access, before the arrays are written). */
@Mixin(SPacketChunkData.class)
public abstract class SPacketChunkDataMixin {

    @Redirect(method = "<init>(Lnet/minecraft/world/chunk/Chunk;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;getWorld()Lnet/minecraft/world/World;"))
    private World starlight$flushBeforePacket(final Chunk chunk) {
        final World world = chunk.getWorld();
        if (!world.isRemote) {
            WorldLight.flush(world);
        }
        return world;
    }
}