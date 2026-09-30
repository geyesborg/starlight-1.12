package com.github.starlight.world;

/** Implemented by World (mixin): the world's Starlight state, or null (client worlds until phase 3). */
public interface StarlightWorld {

    WorldLight starlight$getLight();
}
