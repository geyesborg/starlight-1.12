package com.github.starlight.mixin.client;

import com.github.starlight.world.WorldLight;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The client world's Starlight state (phase 3: the client lights its own chunks). */
@Mixin(WorldClient.class)
public abstract class WorldClientMixin {

    @Unique private WorldLight starlight$light;

    // Overrides WorldMixin's (null) version on the client world
    public WorldLight starlight$getLight() {
        WorldLight light = this.starlight$light;
        if (light == null) {
            light = this.starlight$light = new WorldLight((WorldClient)(Object)this);
        }
        return light;
    }
}