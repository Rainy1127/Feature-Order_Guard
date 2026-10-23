package dev.featureorderguard.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Builds a {@link FeatureGraph} the same way vanilla's feature sorter does, but without any Minecraft type,
 * and can turn a resolved order back into per-step lists.
 * <p>
 * Vanilla rules mirrored here: a feature is identified by {@code equals} (one first-seen index per feature);
 * a node is (feature, step); every source's steps are flattened into one list and each element gets an edge
 * to the next one.
 *
 * @param <H> the per-entry handle the caller wants back when the order is rebuilt (a {@code Holder<PlacedFeature>})
 */
public final class FeatureGraphBuilder<H> {
    private final Function<H, Object> identity;
    private final Function<H, String> idOrNull;
    private final Predicate<H> pinned;

    private final FeatureGraph graph = new FeatureGraph();
    private final Map<Object, Integer> featureIndex = new HashMap<>();
    private final Map<Long, Integer> nodeByFeatureAndStep = new HashMap<>();
    private final List<H> representative = new ArrayList<>();
    private final List<String> sourceIds = new ArrayList<>();
    private int stepCount;

    /**
     * @param identity the object vanilla uses to tell features apart ({@code holder.value()})
     * @param idOrNull registry id of the entry, or {@code null} for an inline (unregistered) feature
     * @param pinned   whether the entry is pinned
     */
    public FeatureGraphBuilder(Function<H, Object> identity, Function<H, String> idOrNull, Predicate<H> pinned) {
        this.identity = identity;
        this.idOrNull = idOrNull;
        this.pinned = pinned;
    }

    public void addSource(String sourceId, List<? extends List<H>> steps) {
        sourceIds.add(sourceId);
        stepCount = Math.max(stepCount, steps.size());
        List<Integer> flat = new ArrayList<>();
        for (int step = 0; step < steps.size(); step++) {
            for (H entry : steps.get(step)) {
                Object feature = identity.apply(entry);
                int index = featureIndex.computeIfAbsent(feature, f -> featureIndex.size());
                long key = ((long) index << 32) | step;
                final int stepFinal = step;
                int node = nodeByFeatureAndStep.computeIfAbsent(key, k -> {
                    String id = idOrNull.apply(entry);
                    representative.add(entry);
                    return graph.addNode(id != null ? id : "inline#" + index, stepFinal, index, pinned.test(entry));
                });
                flat.add(node);
            }
        }
        for (int i = 0; i + 1 < flat.size(); i++) {
            graph.addEdge(flat.get(i), flat.get(i + 1), sourceId);
        }
    }

    public FeatureGraph graph() {
        return graph;
    }

    public List<String> sourceIds() {
        return List.copyOf(sourceIds);
    }

    public int stepCount() {
        return stepCount;
    }

    /** Per-step lists of the representative entries, in the resolved order. Every node appears exactly once. */
    public List<List<H>> rebuild(int[] order) {
        List<List<H>> steps = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) {
            steps.add(new ArrayList<>());
        }
        for (int node : order) {
            steps.get(graph.node(node).step()).add(representative.get(node));
        }
        return steps;
    }
}
