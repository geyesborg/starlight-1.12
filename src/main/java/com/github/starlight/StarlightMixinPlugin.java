package com.github.starlight;

import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Starlight replaces the lighting engine as a whole: with another lighting engine (or Cubic
 * Chunks, whose world layout it doesn't support) present, none of its mixins apply, and the
 * reason is logged, instead of two engines half-applying.
 */
public final class StarlightMixinPlugin implements IMixinConfigPlugin {

    // Resource that identifies each incompatible mod (its mixin config or main class)
    private static final String[][] INCOMPATIBLE = {
            {"Alfheim", "mixins.alfheim.json"},
            {"Phosphor", "mixins.phosphor.json"},
            {"Hesperus", "mixins.hesperus.json"},
            {"Cubic Chunks", "io/github/opencubicchunks/cubicchunks/core/CubicChunks.class"},
    };

    static String conflict;

    @Override
    public void onLoad(final String mixinPackage) {
        final ClassLoader loader = StarlightMixinPlugin.class.getClassLoader();
        for (final String[] mod : INCOMPATIBLE) {
            if (loader.getResource(mod[1]) != null) {
                conflict = mod[0];
                LogManager.getLogger(Starlight.NAME).error(
                        "[Starlight] {} is installed: Starlight stays disabled (one lighting engine at a time). Remove one of them.", mod[0]);
                return;
            }
        }
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return conflict == null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }
}
