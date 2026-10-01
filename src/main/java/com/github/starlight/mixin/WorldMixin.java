package com.github.starlight.mixin;

import com.github.starlight.world.StarlightWorld;
import com.github.starlight.world.WorldLight;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every vanilla light update goes through World.checkLightFor: on server worlds it becomes a queued
 * Starlight change
 */
@Mixin(World.class)
public abstract class WorldMixin implements StarlightWorld {

    @Override
    public WorldLight starlight$getLight() {
        return null; // WorldServerMixin overrides it
    }

    @Inject(method = "checkLightFor", at = @At("HEAD"), cancellable = true)
    private void starlight$queueLightCheck(final EnumSkyBlock type, final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        final WorldLight light = this.starlight$getLight();
        if (light != null) {
            light.queueBlockChange(pos);
            cir.setReturnValue(true);
        }
    }
}
