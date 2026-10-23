package dev.featureorderguard.core;

import java.util.List;
import java.util.SortedSet;

/**
 * Result of {@link CycleResolver#resolve(FeatureGraph)}.
 *
 * @param conflicts    one entry per strongly connected component that contained a cycle, sorted by its smallest node key
 * @param removedEdges every edge that was dropped, sorted by (from key, to key)
 * @param order        all node indices in the final order (vanilla's reverse DFS post-order on the reduced graph)
 */
public record Resolution(List<Conflict> conflicts, List<RemovedEdge> removedEdges, int[] order) {

    public boolean hadCycle() {
        return !conflicts.isEmpty();
    }

    /**
     * One knot of mutually dependent features.
     *
     * @param nodes      node indices, sorted by key
     * @param edges      every edge between those nodes (the conflicting orders), sorted by (from key, to key)
     * @param sources    every source (biome) that contributed one of those edges, sorted
     * @param removed    edges removed to untie this knot
     * @param exact      {@code true}: the removed set has the minimum possible total weight;
     *                   {@code false}: the component was too large, a deterministic greedy pass was used and
     *                   every removed edge is still necessary (no removed edge can be restored without a cycle)
     */
    public record Conflict(List<Integer> nodes, List<FeatureGraph.Edge> edges, SortedSet<String> sources,
                           List<RemovedEdge> removed, boolean exact) {
    }

    /**
     * @param edge         the dropped edge
     * @param weight       its cost (number of sources, plus the pinned penalty when relevant)
     * @param pinnedBroken the edge touched a pinned feature and could not be avoided
     */
    public record RemovedEdge(FeatureGraph.Edge edge, long weight, boolean pinnedBroken) {
    }
}
