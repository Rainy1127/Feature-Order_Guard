package dev.featureorderguard.core;

import java.util.List;

/**
 * Everything the report needs about one sorting call (one chunk generator / biome source). Pure data.
 *
 * @param sourceIds          ids of the sources (biomes) that were sorted, in the order the game gave them
 * @param graph              the dependency graph
 * @param resolution         what the resolver did
 * @param stepNames          names of the generation steps by index ("underground_ores", ...)
 * @param pinnedUnavailable  the pinned tag could not be read at sorting time (tags not bound yet)
 */
public record SourceReport(List<String> sourceIds, FeatureGraph graph, Resolution resolution, List<String> stepNames,
                           boolean pinnedUnavailable) {

    public String stepName(int step) {
        return step >= 0 && step < stepNames.size() ? stepNames.get(step) : "step_" + step;
    }

    /** Stable sort key: biome sources are sorted lazily and in any order, the report must not depend on that. */
    public String sortKey() {
        return String.join(",", sourceIds.stream().sorted().toList());
    }

    /** Identity of the result, used to drop duplicates when the game sorts the same biome source twice. */
    public String signature() {
        StringBuilder builder = new StringBuilder();
        sourceIds.stream().sorted().forEach(id -> builder.append(id).append(','));
        builder.append('|');
        for (Resolution.RemovedEdge removed : resolution.removedEdges()) {
            builder.append(graph.node(removed.edge().from()).key()).append("->")
                    .append(graph.node(removed.edge().to()).key()).append(';');
        }
        return builder.toString();
    }
}
