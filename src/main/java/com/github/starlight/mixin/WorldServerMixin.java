package com.github.starlight.mixin;

import com.github.starlight.world.WorldLight;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A server world's Starlight state, and the end-of-tick flush of queued light changes. */
@Mixin(WorldServer.class)
public abstract class WorldServerMixin {

    @Unique private WorldLight starlight$light;

    // Overrides WorldMixin's (null) version on server worlds
    public WorldLight starlight$getLight() {
        WorldLight light = this.starlight$light;
        if (light == null) {
            // Created on first use: the provider (hasSkyLight) is set by then
            light = this.starlight$light = new WorldLight((WorldServer)(Object)this);
        }
        return light;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void starlight$flushAfterTick(final CallbackInfo ci) {
        final WorldLight light = this.starlight$getLight();
        light.propagateChanges();
        if (Boolean.getBoolean("starlight.memStats") && ((WorldServer)(Object)this).getTotalWorldTime() % 400 == 0 && ((WorldServer)(Object)this).provider.getDimension() == 0) {
            com.github.starlight.Starlight.LOGGER.info("[Starlight mem] {}", light.memStats());
        }
        if (com.github.starlight.world.LightVerifier.ENABLED) {
            com.github.starlight.world.LightVerifier.tick((WorldServer)(Object)this, light);
        }
    }
}
