package com.github.starlight.mixin;

import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.IBlockStatePalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A section's palette: every block state present in it (it never shrinks, so it may list more). */
@Mixin(BlockStateContainer.class)
public interface BlockStateContainerAccessor {

    @Accessor("palette")
    IBlockStatePalette starlight$getPalette();
}