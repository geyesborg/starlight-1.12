package com.github.starlight.world;

import com.github.starlight.mixin.BlockStateContainerAccessor;
import com.github.starlight.mixin.BlockStatePaletteHashMapAccessor;
import com.github.starlight.mixin.BlockStatePaletteLinearAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.chunk.BlockStatePaletteHashMap;
import net.minecraft.world.chunk.BlockStatePaletteLinear;
import net.minecraft.world.chunk.IBlockStatePalette;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Whether a section can contain a light source, from its palette (Moonrise's maybeHas): a state
 * may emit if its light value is above 0, or if its block overrides Forge's position-aware
 * getLightValue (tile entities, fluidlogging, dynamic lights: unknown without the position).
 * The global registry palette (more than 256 states in a section) can't be listed: scan.
 */
final class Emitters {

    private Emitters() {}

    private static final ClassValue<Boolean> POSITIONAL = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            try {
                return type.getMethod("getLightValue", IBlockState.class, IBlockAccess.class, BlockPos.class).getDeclaringClass() != Block.class;
            } catch (final NoSuchMethodException e) {
                return Boolean.TRUE; // can't tell: assume it may emit
            }
        }
    };

    static boolean mayEmit(final IBlockState state) {
        return state != null && (state.getLightValue() > 0 || POSITIONAL.get(state.getBlock().getClass()));
    }

    static boolean mayHaveEmitters(final ExtendedBlockStorage section) {
        final IBlockStatePalette palette = ((BlockStateContainerAccessor)section.getData()).starlight$getPalette();
        if (palette instanceof BlockStatePaletteLinear) {
            final BlockStatePaletteLinearAccessor linear = (BlockStatePaletteLinearAccessor)palette;
            final IBlockState[] states = linear.starlight$getStates();
            for (int i = 0, n = linear.starlight$getArraySize(); i < n; ++i) {
                if (mayEmit(states[i])) {
                    return true;
                }
            }
            return false;
        }
        if (palette instanceof BlockStatePaletteHashMap) {
            for (final IBlockState state : ((BlockStatePaletteHashMapAccessor)palette).starlight$getStatePaletteMap()) {
                if (mayEmit(state)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}