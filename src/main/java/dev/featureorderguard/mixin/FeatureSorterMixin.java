package dev.featureorderguard.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.featureorderguard.SorterBridge;
import java.util.List;
import java.util.function.Function;
import net.minecraft.world.level.biome.FeatureSorter;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The only mixin of the mod. It wraps {@code FeatureSorter.buildFeaturesPerStep(List, Function, boolean)} as a whole
 * (no injection into the method body, so it survives internal changes of the method) and hands the call to
 * {@link SorterBridge}. When there is no cycle the original method runs unchanged.
 */
@Mixin(FeatureSorter.class)
public abstract class FeatureSorterMixin {
    @WrapMethod(method = "buildFeaturesPerStep")
    private static List<?> featureOrderGuard$buildFeaturesPerStep(List<?> sources, Function<?, ?> toFeatures,
                                                                 boolean tryReduce, Operation<List<?>> original) {
        return SorterBridge.wrap(sources, toFeatures, tryReduce, original);
    }
}
