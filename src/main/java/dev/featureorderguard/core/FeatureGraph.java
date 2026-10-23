package dev.featureorderguard.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The "feature must run before feature" graph that vanilla builds while sorting placed features.
 * <p>
 * Pure Java, no Minecraft classes, so it can be unit tested. A node is one placed feature inside one
 * generation step (the same feature in two steps is two nodes, exactly like vanilla). An edge {@code a -> b}
 * means "some feature source (biome) lists {@code b} directly after {@code a}".
 */
public final class FeatureGraph {
    /**
     * @param index        position in {@link #nodes()}
     * @param featureId    registry id of the placed feature ("minecraft:ore_iron") or a synthetic label for inline features
     * @param step         generation step index (ordinal of the decoration step)
     * @param featureIndex first-seen index of the feature across all sources (vanilla's tie breaker)
     * @param pinned       the feature is in {@code #feature_order_guard:pinned}; its edges are only removed as a last resort
     */
    public record Node(int index, String featureId, int step, int featureIndex, boolean pinned) {
        /** Stable, human readable key used for every deterministic choice. */
        public String key() {
            return featureId + "@" + step;
        }
    }

    /** An aggregated edge. {@code sources} are the ids of every source (biome) that asserts it, sorted. */
    public static final class Edge {
        private final int from;
        private final int to;
        private final SortedSet<String> sources = new TreeSet<>();

        Edge(int from, int to) {
            this.from = from;
            this.to = to;
        }

        public int from() {
            return from;
        }

        public int to() {
            return to;
        }

        public SortedSet<String> sources() {
            return Collections.unmodifiableSortedSet(sources);
        }
    }

    /** Vanilla's node order: by step, then by first-seen feature index. */
    public static final Comparator<Node> VANILLA_ORDER =
            Comparator.comparingInt(Node::step).thenComparingInt(Node::featureIndex);

    private final List<Node> nodes = new ArrayList<>();
    private final Map<Long, Edge> edges = new HashMap<>();
    private final List<List<Edge>> outgoing = new ArrayList<>();

    public int addNode(String featureId, int step, int featureIndex, boolean pinned) {
        int index = nodes.size();
        nodes.add(new Node(index, featureId, step, featureIndex, pinned));
        outgoing.add(new ArrayList<>());
        return index;
    }

    /** Records that {@code source} lists {@code to} directly after {@code from}. Repeated calls aggregate. */
    public void addEdge(int from, int to, String source) {
        checkIndex(from);
        checkIndex(to);
        Edge edge = edges.computeIfAbsent(edgeKey(from, to), k -> {
            Edge created = new Edge(from, to);
            outgoing.get(from).add(created);
            return created;
        });
        edge.sources.add(source);
    }

    public List<Node> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public Node node(int index) {
        return nodes.get(index);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public Collection<Edge> edges() {
        return Collections.unmodifiableCollection(edges.values());
    }

    public Edge edge(int from, int to) {
        return edges.get(edgeKey(from, to));
    }

    List<Edge> outgoing(int node) {
        return outgoing.get(node);
    }

    /** Cost of removing an edge: one point per biome that asserts it, a huge penalty if a pinned feature is involved. */
    public long weight(Edge edge) {
        long weight = Math.max(1, edge.sources.size());
        if (nodes.get(edge.from).pinned() || nodes.get(edge.to).pinned()) {
            weight += CycleResolver.PINNED_PENALTY;
        }
        return weight;
    }

    public boolean touchesPinned(Edge edge) {
        return nodes.get(edge.from).pinned() || nodes.get(edge.to).pinned();
    }

    static long edgeKey(int from, int to) {
        return ((long) from << 32) | (to & 0xFFFFFFFFL);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= nodes.size()) {
            throw new IndexOutOfBoundsException("Unknown node " + index);
        }
    }
}
