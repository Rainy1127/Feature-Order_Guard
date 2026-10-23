package dev.featureorderguard;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.featureorderguard.core.CycleResolver;
import dev.featureorderguard.core.FeatureGraphBuilder;
import dev.featureorderguard.core.Resolution;
import dev.featureorderguard.core.SourceReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Called by {@code FeatureSorterMixin} around vanilla's {@code FeatureSorter.buildFeaturesPerStep}.
 * <p>
 * Fast path: vanilla runs unchanged (with its slow "find the guilty biomes" pass switched off). Only when vanilla
 * throws, the graph is rebuilt here, resolved by {@link CycleResolver} and handed back to vanilla as one already
 * consistent source, so vanilla itself still produces the final step data.
 */
public final class SorterBridge {
    public static final TagKey<PlacedFeature> PINNED =
            TagKey.create(Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath(FeatureOrderGuard.MODID, "pinned"));

    /** The single fake source handed back to vanilla. Its identity never matters, only the feature lists. */
    private static final Object SYNTHETIC_SOURCE = new Object() {
        @Override
        public String toString() {
            return "feature_order_guard:resolved_order";
        }
    };

    private SorterBridge() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<?> wrap(List<?> sources, Function<?, ?> toFeatures, boolean tryReduce, Operation<List<?>> original) {
        // Vanilla's own inner calls (tryReduce == false) and the CRASH strategy pass straight through.
        if (!tryReduce) {
            return original.call(sources, toFeatures, false);
        }
        Strategy strategy = GuardConfig.strategy();
        if (strategy == Strategy.CRASH) {
            return original.call(sources, toFeatures, true);
        }

        IllegalStateException vanillaFailure;
        try {
            return original.call(sources, toFeatures, false);
        } catch (IllegalStateException e) {
            vanillaFailure = e;
        }

        Analysis analysis;
        try {
            analysis = analyse(sources, (Function<Object, List<HolderSet<PlacedFeature>>>) toFeatures);
        } catch (RuntimeException e) {
            FeatureOrderGuard.LOGGER.error("Feature Order Guard could not analyse the feature order, falling back to vanilla", e);
            return original.call(sources, toFeatures, true);
        }
        if (!analysis.report().resolution().hadCycle()) {
            // Vanilla failed for a reason that is not a cycle: let vanilla report it as usual.
            return original.call(sources, toFeatures, true);
        }

        ReportStore.record(analysis.report(), strategy);
        if (strategy == Strategy.REPORT_ONLY) {
            throw new IllegalStateException("Feature order cycle found (Feature Order Guard: strategy REPORT_ONLY, see "
                    + ReportStore.REPORT_FILE_NAME + " in the logs folder)", vanillaFailure);
        }

        List<HolderSet<PlacedFeature>> resolved = new ArrayList<>();
        for (List<Holder<PlacedFeature>> step : analysis.builder().rebuild(analysis.report().resolution().order())) {
            resolved.add(HolderSet.direct(step));
        }
        Function<Object, List<HolderSet<PlacedFeature>>> single = ignored -> resolved;
        return original.call(List.of(SYNTHETIC_SOURCE), single, false);
    }

    private record Analysis(FeatureGraphBuilder<Holder<PlacedFeature>> builder, SourceReport report) {
    }

    private static Analysis analyse(List<?> sources, Function<Object, List<HolderSet<PlacedFeature>>> toFeatures) {
        boolean[] pinnedUnavailable = {false};
        FeatureGraphBuilder<Holder<PlacedFeature>> builder = new FeatureGraphBuilder<>(
                Holder::value,
                holder -> holder.unwrapKey().map(key -> key.identifier().toString()).orElse(null),
                holder -> {
                    try {
                        return holder.is(PINNED);
                    } catch (IllegalStateException tagsNotBound) {
                        pinnedUnavailable[0] = true;
                        return false;
                    }
                });

        for (Object source : sources) {
            List<HolderSet<PlacedFeature>> sets = toFeatures.apply(source);
            List<List<Holder<PlacedFeature>>> steps = new ArrayList<>(sets.size());
            for (HolderSet<PlacedFeature> set : sets) {
                List<Holder<PlacedFeature>> step = new ArrayList<>(set.size());
                set.forEach(step::add);
                steps.add(step);
            }
            builder.addSource(sourceId(source), steps);
        }

        Resolution resolution = CycleResolver.resolve(builder.graph());
        SourceReport report = new SourceReport(builder.sourceIds(), builder.graph(), resolution, stepNames(), pinnedUnavailable[0]);
        return new Analysis(builder, report);
    }

    private static String sourceId(Object source) {
        if (source instanceof Holder<?> holder) {
            return holder.unwrapKey().map(ResourceKey::identifier).map(Identifier::toString).orElse("(inline biome)");
        }
        return String.valueOf(source);
    }

    private static List<String> stepNames() {
        List<String> names = new ArrayList<>();
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            names.add(step.name().toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
