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
 * Every vanilla light update (block and sky, all callers) goes through World.checkLightFor. On a
 * server world it becomes a queued Starlight block change, applied at the next flush point; the
 * return value ("light was updated") stays true as vanilla's does for loaded areas.
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
