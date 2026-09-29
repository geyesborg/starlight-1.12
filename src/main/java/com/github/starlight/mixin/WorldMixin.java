package com.github.starlight.mixin;

import com.github.starlight.Starlight;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entry point of every vanilla light update (block and sky). Phase 0: counts
 * calls to prove the hook applies in dev and production; the engine replaces
 * this method in a later phase.
 */
@Mixin(World.class)
public abstract class WorldMixin {

    @Unique
    private static long starlight$lightChecks;

    @Inject(method = "checkLightFor", at = @At("HEAD"))
    private void starlight$countLightCheck(EnumSkyBlock type, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (starlight$lightChecks++ == 0) {
            Starlight.LOGGER.info("[Starlight] lighting hook active (World.checkLightFor)");
        }
    }
}
