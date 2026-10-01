package com.github.starlight.mixin.client;

import com.github.starlight.world.ClientLightVerifier;
import com.github.starlight.world.StarlightWorld;
import com.github.starlight.world.WorldLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Once per frame before the world renders: queued changes are applied (a placed torch lights up the
 * same frame) and arriving chunks are lit
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Unique
    private static final long STARLIGHT_FRAME_BUDGET_NANOS = Long.getLong("starlight.clientBudgetMicros", 1000L) * 1000L;

    @Shadow @Final private Minecraft mc;

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void starlight$clientFrame(final float partialTicks, final long nanoTime, final CallbackInfo ci) {
        if (this.mc.world == null) {
            return;
        }
        final WorldLight light = ((StarlightWorld)this.mc.world).starlight$getLight();
        if (light != null) {
            light.clientFrame(STARLIGHT_FRAME_BUDGET_NANOS);
            if (ClientLightVerifier.ENABLED) {
                ClientLightVerifier.frame(this.mc, light);
            }
        }
    }
}