package com.github.starlight.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.world.chunk.BlockStatePaletteLinear;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockStatePaletteLinear.class)
public interface BlockStatePaletteLinearAccessor {

    @Accessor("states")
    IBlockState[] starlight$getStates();

    @Accessor("arraySize")
    int starlight$getArraySize();
}