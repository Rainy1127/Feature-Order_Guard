package dev.featureorderguard.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds every cycle in a {@link FeatureGraph}, removes a minimum-weight set of edges and produces the final order.
 * <p>
 * Pure Java and fully deterministic: every choice is made by weight first and then by the textual node keys,
 * never by hash order or memory addresses, so the same set of mods always gives the same result.
 *
 * <ul>
 *   <li>Cycles are found per strongly connected component (iterative Tarjan, no recursion).</li>
 *   <li>Components with up to {@value #EXACT_LIMIT} nodes are solved exactly: a dynamic programme over node subsets
 *       finds the linear order with the smallest total weight of backward edges (= a minimum feedback arc set).</li>
 *   <li>Larger components (very rare in practice) use a deterministic greedy pass followed by a clean-up pass that
 *       restores every removed edge that is not actually needed, so the result is minimal by inclusion.</li>
 *   <li>The final order is computed exactly like vanilla's: nodes by (step, first-seen index), depth-first, reverse
 *       post-order. Features that were never in conflict therefore keep the order vanilla would have given them.</li>
 * </ul>
 */
public final class CycleResolver {
    /** Components up to this size are solved exactly (2^16 states, a few million cheap operations at worst). */
    public static final int EXACT_LIMIT = 16;
    /** Added to the weight of any edge that touches a pinned feature. */
    public static final long PINNED_PENALTY = 1_000_000_000L;

    private CycleResolver() {
    }

    public static Resolution resolve(FeatureGraph graph) {
        int n = graph.nodeCount();
        Comparator<Integer> byKey = Comparator.comparing(i -> graph.node(i).key());

        List<List<Integer>> components = stronglyConnectedComponents(graph, byKey);
        Set<Long> removedKeys = new HashSet<>();
        List<Resolution.Conflict> conflicts = new ArrayList<>();
        List<Resolution.RemovedEdge> allRemoved = new ArrayList<>();

        for (List<Integer> component : components) {
            boolean selfLoop = component.size() == 1 && graph.edge(component.get(0), component.get(0)) != null;
            if (component.size() < 2 && !selfLoop) {
                continue;
            }
            List<FeatureGraph.Edge> inner = innerEdges(graph, component, byKey);
            List<FeatureGraph.Edge> removed;
            boolean exact;
            if (component.size() <= EXACT_LIMIT) {
                removed = exactFeedbackArcSet(graph, component, inner);
                exact = true;
            } else {
                removed = greedyFeedbackArcSet(graph, component, inner, byKey);
                exact = false;
            }
            removed.sort(edgeOrder(graph));
            List<Resolution.RemovedEdge> removedViews = new ArrayList<>();
            for (FeatureGraph.Edge edge : removed) {
                removedKeys.add(FeatureGraph.edgeKey(edge.from(), edge.to()));
                removedViews.add(new Resolution.RemovedEdge(edge, graph.weight(edge), graph.touchesPinned(edge)));
            }
            TreeSet<String> sources = new TreeSet<>();
            inner.forEach(edge -> sources.addAll(edge.sources()));
            conflicts.add(new Resolution.Conflict(List.copyOf(component), List.copyOf(inner), sources,
                    List.copyOf(removedViews), exact));
            allRemoved.addAll(removedViews);
        }

        allRemoved.sort(Comparator.comparing((Resolution.RemovedEdge r) -> graph.node(r.edge().from()).key())
                .thenComparing(r -> graph.node(r.edge().to()).key()));
        int[] order = vanillaOrder(graph, removedKeys);
        if (order.length != n) {
            throw new IllegalStateException("Internal error: order lost nodes");
        }
        return new Resolution(List.copyOf(conflicts), List.copyOf(allRemoved), order);
    }

    // ------------------------------------------------------------------ components

    /** Iterative Tarjan. Components are returned sorted internally by key, and sorted by their first key. */
    static List<List<Integer>> stronglyConnectedComponents(FeatureGraph graph, Comparator<Integer> byKey) {
        int n = graph.nodeCount();
        int[][] adjacency = sortedAdjacency(graph, byKey, Set.of());
        Integer[] roots = new Integer[n];
        for (int i = 0; i < n; i++) {
            roots[i] = i;
        }
        Arrays.sort(roots, byKey);

        int[] index = new int[n];
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(index, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        int[] cursor = new int[n];
        int counter = 0;
        List<List<Integer>> result = new ArrayList<>();

        for (int root : roots) {
            if (index[root] != -1) {
                continue;
            }
            Deque<Integer> call = new ArrayDeque<>();
            call.push(root);
            index[root] = low[root] = counter++;
            stack.push(root);
            onStack[root] = true;
            while (!call.isEmpty()) {
                int v = call.peek();
                if (cursor[v] < adjacency[v].length) {
                    int w = adjacency[v][cursor[v]++];
                    if (index[w] == -1) {
                        index[w] = low[w] = counter++;
                        stack.push(w);
                        onStack[w] = true;
                        call.push(w);
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                } else {
                    call.pop();
                    if (!call.isEmpty()) {
                        int parent = call.peek();
                        low[parent] = Math.min(low[parent], low[v]);
                    }
                    if (low[v] == index[v]) {
                        List<Integer> component = new ArrayList<>();
                        int w;
                        do {
                            w = stack.pop();
                            onStack[w] = false;
                            component.add(w);
                        } while (w != v);
                        component.sort(byKey);
                        result.add(component);
                    }
                }
            }
        }
        result.sort(Comparator.comparing(c -> graph.node(c.get(0)).key()));
        return result;
    }

    private static List<FeatureGraph.Edge> innerEdges(FeatureGraph graph, List<Integer> component, Comparator<Integer> byKey) {
        Set<Integer> members = new HashSet<>(component);
        List<FeatureGraph.Edge> inner = new ArrayList<>();
        for (int v : component) {
            for (FeatureGraph.Edge edge : graph.outgoing(v)) {
                if (members.contains(edge.to())) {
                    inner.add(edge);
                }
            }
        }
        inner.sort(edgeOrder(graph));
        return inner;
    }

    private static Comparator<FeatureGraph.Edge> edgeOrder(FeatureGraph graph) {
        return Comparator.comparing((FeatureGraph.Edge e) -> graph.node(e.from()).key())
                .thenComparing(e -> graph.node(e.to()).key());
    }

    // ------------------------------------------------------------------ exact solver

    /**
     * Minimum feedback arc set by dynamic programming over subsets: dp[S] = cheapest way to place the nodes of S first.
     * Placing v after S costs the weight of every edge v -> u with u in S (those become backward edges).
     * Ties keep the first candidate in key order, so the result is deterministic.
     */
    static List<FeatureGraph.Edge> exactFeedbackArcSet(FeatureGraph graph, List<Integer> component, List<FeatureGraph.Edge> inner) {
        int k = component.size();
        if (k == 1) {
            return new ArrayList<>(inner);
        }
        int[] local = new int[graph.nodeCount()];
        for (int i = 0; i < k; i++) {
            local[component.get(i)] = i;
        }
        long[][] weight = new long[k][k];
        List<FeatureGraph.Edge> selfLoops = new ArrayList<>();
        for (FeatureGraph.Edge edge : inner) {
            if (edge.from() == edge.to()) {
                selfLoops.add(edge);
            } else {
                weight[local[edge.from()]][local[edge.to()]] = graph.weight(edge);
            }
        }

        int full = (1 << k) - 1;
        long[] dp = new long[1 << k];
        int[] lastPlaced = new int[1 << k];
        Arrays.fill(dp, Long.MAX_VALUE);
        dp[0] = 0;
        for (int set = 0; set < full; set++) {
            if (dp[set] == Long.MAX_VALUE) {
                continue;
            }
            for (int v = 0; v < k; v++) {
                if ((set & (1 << v)) != 0) {
                    continue;
                }
                long cost = dp[set];
                for (int u = 0; u < k; u++) {
                    if ((set & (1 << u)) != 0) {
                        cost += weight[v][u];
                    }
                }
                int next = set | (1 << v);
                if (cost < dp[next]) {
                    dp[next] = cost;
                    lastPlaced[next] = v;
                }
            }
        }

        int[] position = new int[k];
        int set = full;
        for (int pos = k - 1; pos >= 0; pos--) {
            int v = lastPlaced[set];
            position[v] = pos;
            set &= ~(1 << v);
        }

        List<FeatureGraph.Edge> removed = new ArrayList<>(selfLoops);
        for (FeatureGraph.Edge edge : inner) {
            if (edge.from() != edge.to() && position[local[edge.from()]] > position[local[edge.to()]]) {
                removed.add(edge);
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ greedy solver (large components)

    static List<FeatureGraph.Edge> greedyFeedbackArcSet(FeatureGraph graph, List<Integer> component,
                                                       List<FeatureGraph.Edge> inner, Comparator<Integer> byKey) {
        Set<Long> removed = new HashSet<>();
        List<FeatureGraph.Edge> removedInOrder = new ArrayList<>();
        Comparator<FeatureGraph.Edge> cheapestFirst = Comparator.comparingLong(graph::weight).thenComparing(edgeOrder(graph));

        while (true) {
            List<FeatureGraph.Edge> cycle = findCycle(graph, component, removed, byKey);
            if (cycle == null) {
                break;
            }
            FeatureGraph.Edge cheapest = cycle.stream().min(cheapestFirst).orElseThrow();
            removed.add(FeatureGraph.edgeKey(cheapest.from(), cheapest.to()));
            removedInOrder.add(cheapest);
        }

        // Clean-up: put back every edge that does not recreate a cycle (latest removals first).
        List<FeatureGraph.Edge> result = new ArrayList<>();
        for (int i = removedInOrder.size() - 1; i >= 0; i--) {
            FeatureGraph.Edge edge = removedInOrder.get(i);
            long key = FeatureGraph.edgeKey(edge.from(), edge.to());
            removed.remove(key);
            if (findCycle(graph, component, removed, byKey) != null) {
                removed.add(key);
                result.add(edge);
            }
        }
        return result;
    }

    /** Deterministic DFS over the component without the removed edges; returns the edges of one cycle or null. */
    private static List<FeatureGraph.Edge> findCycle(FeatureGraph graph, List<Integer> component, Set<Long> removed,
                                                     Comparator<Integer> byKey) {
        Set<Integer> members = new HashSet<>(component);
        int n = graph.nodeCount();
        int[] state = new int[n];
        int[] parent = new int[n];
        int[][] adjacency = sortedAdjacency(graph, byKey, removed);
        int[] cursor = new int[n];
        for (int root : component) {
            if (state[root] != 0) {
                continue;
            }
            Deque<Integer> call = new ArrayDeque<>();
            call.push(root);
            state[root] = 1;
            parent[root] = -1;
            while (!call.isEmpty()) {
                int v = call.peek();
                if (cursor[v] < adjacency[v].length) {
                    int w = adjacency[v][cursor[v]++];
                    if (!members.contains(w)) {
                        continue;
                    }
                    if (state[w] == 0) {
                        state[w] = 1;
                        parent[w] = v;
                        call.push(w);
                    } else if (state[w] == 1) {
                        List<FeatureGraph.Edge> cycle = new ArrayList<>();
                        cycle.add(graph.edge(v, w));
                        int x = v;
                        while (x != w) {
                            cycle.add(graph.edge(parent[x], x));
                            x = parent[x];
                        }
                        return cycle;
                    }
                } else {
                    state[v] = 2;
                    call.pop();
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ final order

    /**
     * Same traversal as vanilla's feature sorter: roots in (step, first-seen index) order, neighbours in the same
     * order, depth-first, reverse post-order. Throws if a cycle is still present (that would be a bug).
     */
    static int[] vanillaOrder(FeatureGraph graph, Set<Long> removed) {
        int n = graph.nodeCount();
        Comparator<Integer> vanilla = (a, b) -> FeatureGraph.VANILLA_ORDER.compare(graph.node(a), graph.node(b));
        int[][] adjacency = sortedAdjacency(graph, vanilla, removed);
        Integer[] roots = new Integer[n];
        for (int i = 0; i < n; i++) {
            roots[i] = i;
        }
        Arrays.sort(roots, vanilla);

        int[] state = new int[n];
        int[] cursor = new int[n];
        int[] postOrder = new int[n];
        int written = 0;
        for (int root : roots) {
            if (state[root] != 0) {
                continue;
            }
            Deque<Integer> call = new ArrayDeque<>();
            call.push(root);
            state[root] = 1;
            while (!call.isEmpty()) {
                int v = call.peek();
                if (cursor[v] < adjacency[v].length) {
                    int w = adjacency[v][cursor[v]++];
                    if (state[w] == 1) {
                        throw new IllegalStateException("Internal error: a cycle survived the resolution");
                    }
                    if (state[w] == 0) {
                        state[w] = 1;
                        call.push(w);
                    }
                } else {
                    state[v] = 2;
                    call.pop();
                    postOrder[written++] = v;
                }
            }
        }
        int[] order = new int[written];
        for (int i = 0; i < written; i++) {
            order[i] = postOrder[written - 1 - i];
        }
        return order;
    }

    private static int[][] sortedAdjacency(FeatureGraph graph, Comparator<Integer> order, Set<Long> removed) {
        int n = graph.nodeCount();
        int[][] adjacency = new int[n][];
        for (int v = 0; v < n; v++) {
            List<Integer> targets = new ArrayList<>();
            for (FeatureGraph.Edge edge : graph.outgoing(v)) {
                if (!removed.contains(FeatureGraph.edgeKey(edge.from(), edge.to()))) {
                    targets.add(edge.to());
                }
            }
            targets.sort(order);
            adjacency[v] = targets.stream().mapToInt(Integer::intValue).toArray();
        }
        return adjacency;
    }
}
