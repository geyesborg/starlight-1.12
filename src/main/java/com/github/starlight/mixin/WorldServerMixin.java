package com.github.starlight.mixin;

import com.github.starlight.Starlight;
import com.github.starlight.world.LightVerifier;
import com.github.starlight.world.WorldLight;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
            Starlight.LOGGER.info("[Starlight mem] {}", light.memStats());
        }
        if (LightVerifier.ENABLED || LightVerifier.STALE_EDGE_TEST || LightVerifier.BURST > 0) {
            LightVerifier.tick((WorldServer)(Object)this, light);
        }
    }
}
