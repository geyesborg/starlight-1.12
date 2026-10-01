package com.github.starlight.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.IntIdentityHashBiMap;
import net.minecraft.world.chunk.BlockStatePaletteHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockStatePaletteHashMap.class)
public interface BlockStatePaletteHashMapAccessor {

    @Accessor("statePaletteMap")
    IntIdentityHashBiMap<IBlockState> starlight$getStatePaletteMap();
}