package dev.featureorderguard.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CycleResolverTest {

    /** Features are plain strings; a "biome" is a list of steps, each step a list of feature ids. */
    private static FeatureGraphBuilder<String> builder(Set<String> pinned) {
        return new FeatureGraphBuilder<>(s -> s, s -> s.startsWith("inline") ? null : s, pinned::contains);
    }

    private static List<String> order(FeatureGraphBuilder<String> b, Resolution r) {
        List<String> flat = new ArrayList<>();
        b.rebuild(r.order()).forEach(flat::addAll);
        return flat;
    }

    private static List<String> removed(FeatureGraph g, Resolution r) {
        return r.removedEdges().stream().map(e -> ReportFormatter.edgeText(g, e.edge())).toList();
    }

    @Test
    void noCycleKeepsVanillaOrder() {
        var b = builder(Set.of());
        b.addSource("test:a", List.of(List.of("m:x", "m:y", "m:z")));
        b.addSource("test:b", List.of(List.of("m:x", "m:z")));
        Resolution r = CycleResolver.resolve(b.graph());
        assertFalse(r.hadCycle());
        assertTrue(r.removedEdges().isEmpty());
        assertEquals(List.of("m:x", "m:y", "m:z"), order(b, r));
    }

    @Test
    void twoBiomesWithOppositeOrderAreResolved() {
        var b = builder(Set.of());
        b.addSource("test:plains", List.of(List.of("m:a", "m:b")));
        b.addSource("test:forest", List.of(List.of("m:b", "m:a")));
        Resolution r = CycleResolver.resolve(b.graph());
        assertTrue(r.hadCycle());
        assertEquals(1, r.conflicts().size());
        assertTrue(r.conflicts().get(0).exact());
        // Equal weights: the key-smaller feature keeps its place, so b -> a is dropped.
        assertEquals(List.of("m:b -> m:a"), removed(b.graph(), r));
        assertEquals(List.of("m:a", "m:b"), order(b, r));
    }

    @Test
    void cheapestEdgeIsRemoved() {
        var b = builder(Set.of());
        // Three biomes want a before b, one wants b before a: dropping b -> a affects only one biome.
        b.addSource("test:1", List.of(List.of("m:b", "m:a")));
        b.addSource("test:2", List.of(List.of("m:a", "m:b")));
        b.addSource("test:3", List.of(List.of("m:a", "m:b")));
        b.addSource("test:4", List.of(List.of("m:a", "m:b")));
        Resolution r = CycleResolver.resolve(b.graph());
        assertEquals(List.of("m:b -> m:a"), removed(b.graph(), r));
        assertEquals(1, r.removedEdges().get(0).weight());

        var c = builder(Set.of());
        c.addSource("test:1", List.of(List.of("m:a", "m:b")));
        c.addSource("test:2", List.of(List.of("m:b", "m:a")));
        c.addSource("test:3", List.of(List.of("m:b", "m:a")));
        Resolution r2 = CycleResolver.resolve(c.graph());
        assertEquals(List.of("m:a -> m:b"), removed(c.graph(), r2));
        assertEquals(List.of("m:b", "m:a"), order(c, r2));
    }

    @Test
    void threeCycleNeedsOnlyOneRemoval() {
        var b = builder(Set.of());
        b.addSource("test:1", List.of(List.of("m:x", "m:y", "m:z")));
        b.addSource("test:2", List.of(List.of("m:z", "m:x")));
        Resolution r = CycleResolver.resolve(b.graph());
        assertEquals(1, r.removedEdges().size());
        List<String> result = order(b, r);
        assertEquals(3, result.size());
        assertEquals(Set.of("m:x", "m:y", "m:z"), new HashSet<>(result));
    }

    @Test
    void pinnedEdgesAreAvoided() {
        var b = builder(Set.of("m:a"));
        b.addSource("test:1", List.of(List.of("m:a", "m:b")));
        b.addSource("test:2", List.of(List.of("m:b", "m:c")));
        b.addSource("test:3", List.of(List.of("m:c", "m:a")));
        Resolution r = CycleResolver.resolve(b.graph());
        // a -> b and c -> a touch the pinned feature, so b -> c must be the one removed.
        assertEquals(List.of("m:b -> m:c"), removed(b.graph(), r));
        assertFalse(r.removedEdges().get(0).pinnedBroken());
    }

    @Test
    void unavoidablePinnedEdgeIsFlagged() {
        var b = builder(Set.of("m:a", "m:b"));
        b.addSource("test:1", List.of(List.of("m:a", "m:b")));
        b.addSource("test:2", List.of(List.of("m:b", "m:a")));
        Resolution r = CycleResolver.resolve(b.graph());
        assertEquals(1, r.removedEdges().size());
        assertTrue(r.removedEdges().get(0).pinnedBroken());
    }

    @Test
    void duplicateFeatureInOneBiomeIsASelfLoopOrCycle() {
        var b = builder(Set.of());
        b.addSource("test:1", List.of(List.of("m:a", "m:a")));
        Resolution r = CycleResolver.resolve(b.graph());
        assertTrue(r.hadCycle());
        assertEquals(List.of("m:a -> m:a"), removed(b.graph(), r));
        assertEquals(List.of("m:a"), order(b, r));
    }

    @Test
    void stepsStaySeparateAndEveryNodeIsKept() {
        var b = builder(Set.of());
        b.addSource("test:1", List.of(List.of("m:a", "m:b"), List.of("m:c")));
        b.addSource("test:2", List.of(List.of("m:b", "m:a"), List.of("m:c", "m:a")));
        Resolution r = CycleResolver.resolve(b.graph());
        List<List<String>> steps = b.rebuild(r.order());
        assertEquals(2, steps.size());
        assertEquals(Set.of("m:a", "m:b"), new HashSet<>(steps.get(0)));
        // m:a in step 1 is a different node than m:a in step 0 (vanilla does the same).
        assertEquals(List.of("m:c", "m:a"), steps.get(1));
        assertEquals(1, r.conflicts().size());
    }

    @Test
    void resultDoesNotDependOnSourceOrder() {
        List<List<List<String>>> biomes = List.of(
                List.of(List.of("m:a", "m:b", "m:c")),
                List.of(List.of("m:c", "m:a")),
                List.of(List.of("m:b", "m:d", "m:a")),
                List.of(List.of("m:d", "m:c")));
        List<String> first = null;
        for (int rotation = 0; rotation < biomes.size(); rotation++) {
            var b = builder(Set.of());
            for (int i = 0; i < biomes.size(); i++) {
                int idx = (i + rotation) % biomes.size();
                b.addSource("test:" + idx, biomes.get(idx));
            }
            Resolution r = CycleResolver.resolve(b.graph());
            List<String> removed = removed(b.graph(), r);
            if (first == null) {
                first = removed;
            }
            assertEquals(first, removed, "removed edges must not depend on biome order");
        }
    }

    @Test
    void exactSolverBeatsNaiveOnKnownGraph() {
        // The chain a-b-c-d is asked by three biomes; two single biomes close cycles. Minimum: drop the two light edges.
        var b = builder(Set.of());
        b.addSource("w:1", List.of(List.of("m:a", "m:b", "m:c", "m:d")));
        b.addSource("w:2", List.of(List.of("m:a", "m:b", "m:c", "m:d")));
        b.addSource("w:5", List.of(List.of("m:a", "m:b", "m:c", "m:d")));
        b.addSource("w:3", List.of(List.of("m:d", "m:a")));
        b.addSource("w:4", List.of(List.of("m:c", "m:a")));
        Resolution r = CycleResolver.resolve(b.graph());
        long total = r.removedEdges().stream().mapToLong(Resolution.RemovedEdge::weight).sum();
        assertEquals(2, total);
        assertEquals(List.of("m:c -> m:a", "m:d -> m:a"), removed(b.graph(), r));
    }

    @Test
    void largeComponentUsesGreedyAndStaysMinimalByInclusion() {
        var b = builder(Set.of());
        int n = CycleResolver.EXACT_LIMIT + 4;
        List<String> ring = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ring.add(String.format("m:f%02d", i));
        }
        b.addSource("big:forward", List.of(ring));
        b.addSource("big:back", List.of(List.of(ring.get(n - 1), ring.get(0))));
        b.addSource("big:chord", List.of(List.of(ring.get(n / 2), ring.get(1))));
        Resolution r = CycleResolver.resolve(b.graph());
        assertEquals(1, r.conflicts().size());
        assertFalse(r.conflicts().get(0).exact());
        assertTrue(r.removedEdges().size() <= 2);
        assertEquals(n, r.order().length);
    }

    @Test
    void inlineFeaturesGetStableLabels() {
        var b = builder(Set.of());
        b.addSource("test:1", List.of(List.of("m:a", "inline-1")));
        assertEquals("inline#1", b.graph().node(1).featureId());
    }

    @Test
    void orderMatchesVanillaTraversal() {
        var b = builder(Set.of());
        b.addSource("t:1", List.of(List.of("m:a", "m:c")));
        b.addSource("t:2", List.of(List.of("m:b", "m:c")));
        Resolution r = CycleResolver.resolve(b.graph());
        // Vanilla: roots a(0), c(1), b(2) in first-seen order; DFS(a) -> c, then b. Reverse post-order: b, a, c.
        int[] expected = {b.graph().nodes().stream().filter(x -> x.featureId().equals("m:b")).findFirst().orElseThrow().index(),
                0, 1};
        assertArrayEquals(expected, r.order());
    }
}
