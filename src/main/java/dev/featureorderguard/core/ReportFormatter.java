package dev.featureorderguard.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * Turns resolver results into text: the full report file, short log lines and the chat summary. Pure Java.
 * The output contains no timestamps or hash-ordered data, so the same mods always produce the same text.
 */
public final class ReportFormatter {
    private static final String RULE = "=".repeat(78);
    private static final String THIN_RULE = "-".repeat(78);

    private ReportFormatter() {
    }

    public static int cycleCount(Collection<SourceReport> reports) {
        return reports.stream().mapToInt(r -> r.resolution().conflicts().size()).sum();
    }

    public static int removedCount(Collection<SourceReport> reports) {
        return reports.stream().mapToInt(r -> r.resolution().removedEdges().size()).sum();
    }

    public static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : "(inline)";
    }

    /** "a -> b" for an edge. */
    public static String edgeText(FeatureGraph graph, FeatureGraph.Edge edge) {
        return graph.node(edge.from()).featureId() + " -> " + graph.node(edge.to()).featureId();
    }

    /** The complete report file. */
    public static String fullReport(List<SourceReport> unsorted, String strategy, String modVersion) {
        List<SourceReport> reports = sorted(unsorted);
        StringBuilder body = new StringBuilder();
        if (reports.isEmpty()) {
            body.append("Result: no feature order cycles were found. Nothing was changed.\n");
        } else {
            body.append("Result: ").append(cycleCount(reports)).append(" cycle(s) in ").append(reports.size())
                    .append(" biome source(s), ").append(removedCount(reports)).append(" ordering edge(s) removed.\n");
            if (!"RESOLVE".equals(strategy)) {
                body.append("Nothing was changed: strategy ").append(strategy).append(" only reports. The game stopped.\n");
            }
            for (int i = 0; i < reports.size(); i++) {
                appendSource(body, reports.get(i), i + 1, reports.size());
            }
            body.append('\n').append(RULE).append('\n');
            body.append("How to read this:\n");
            body.append("  Every biome lists its placed features in order. Two biomes that list the same two features in\n");
            body.append("  opposite orders make a cycle, and vanilla crashes with \"Feature order cycle found\".\n");
            body.append("  Feature Order Guard drops the cheapest set of \"A runs before B\" rules (fewest biomes affected)\n");
            body.append("  so that one global order exists. Only the listed pairs change their relative order, and only\n");
            body.append("  in the listed biomes. Everything else keeps vanilla's order.\n");
            body.append("  To protect a feature, add it to the placed feature tag #feature_order_guard:pinned.\n");
            body.append("  To fix the cause, make the mods/datapacks named above agree on one order.\n");
        }

        String text = body.toString();
        StringBuilder out = new StringBuilder();
        out.append("Feature Order Guard ").append(modVersion).append(" - feature order report\n");
        out.append("Strategy: ").append(strategy).append('\n');
        out.append("Fingerprint: ").append(fingerprint(strategy + "\n" + text)).append(
                "  (same mods and datapacks = same fingerprint)\n\n");
        out.append(text);
        return out.toString();
    }

    private static void appendSource(StringBuilder out, SourceReport report, int number, int total) {
        FeatureGraph graph = report.graph();
        out.append('\n').append(RULE).append('\n');
        out.append("Biome source ").append(number).append(" of ").append(total).append(": ")
                .append(report.sourceIds().size()).append(" biomes (").append(namespaceCounts(report.sourceIds())).append(")\n");
        if (report.pinnedUnavailable()) {
            out.append("Note: the #feature_order_guard:pinned tag could not be read yet at sorting time and was ignored.\n");
        }
        out.append(THIN_RULE).append('\n');

        List<Resolution.Conflict> conflicts = report.resolution().conflicts();
        for (int c = 0; c < conflicts.size(); c++) {
            Resolution.Conflict conflict = conflicts.get(c);
            int step = graph.node(conflict.nodes().get(0)).step();
            out.append("Cycle ").append(c + 1).append(" in step \"").append(report.stepName(step)).append("\" (")
                    .append(conflict.nodes().size()).append(conflict.nodes().size() == 1 ? " feature" : " features")
                    .append(conflict.exact() ? ", minimum removal" : ", large knot: greedy removal, every removal still needed")
                    .append(")\n");

            out.append("  Features:\n");
            TreeSet<String> featureNamespaces = new TreeSet<>();
            for (int node : conflict.nodes()) {
                String id = graph.node(node).featureId();
                featureNamespaces.add(namespace(id));
                out.append("    ").append(id).append("   [").append(namespace(id)).append(']')
                        .append(graph.node(node).pinned() ? "   (pinned)" : "").append('\n');
            }

            out.append("  Conflicting orders (which biomes ask for what):\n");
            for (FeatureGraph.Edge edge : conflict.edges()) {
                out.append("    ").append(edgeText(graph, edge)).append('\n');
                out.append("        ").append(biomeList(edge.sources())).append('\n');
            }

            out.append("  Removed (").append(conflict.removed().size()).append("):\n");
            for (Resolution.RemovedEdge removed : conflict.removed()) {
                out.append("    ").append(edgeText(graph, removed.edge()))
                        .append("   (biomes affected: ").append(removed.edge().sources().size()).append(")")
                        .append(removed.pinnedBroken() ? "   WARNING: touches a pinned feature, no other way out" : "")
                        .append('\n');
                out.append("        effect: in ").append(String.join(", ", removed.edge().sources()))
                        .append(", these two may now run in the opposite order.\n");
            }

            TreeSet<String> biomeNamespaces = new TreeSet<>();
            conflict.sources().forEach(id -> biomeNamespaces.add(namespace(id)));
            out.append("  Biomes involved: ").append(String.join(", ", conflict.sources())).append('\n');
            out.append("  Mods involved: features from ").append(String.join(", ", featureNamespaces))
                    .append("; biomes from ").append(String.join(", ", biomeNamespaces)).append("\n\n");
        }
    }

    /** Reports in a stable order, independent of which dimension happened to be sorted first. */
    public static List<SourceReport> sorted(Collection<SourceReport> reports) {
        List<SourceReport> list = new ArrayList<>(reports);
        list.sort(java.util.Comparator.comparing(SourceReport::sortKey).thenComparing(SourceReport::signature));
        return list;
    }

    /** One short line per removed edge, for the log. */
    public static List<String> logLines(SourceReport report) {
        List<String> lines = new ArrayList<>();
        FeatureGraph graph = report.graph();
        for (Resolution.Conflict conflict : report.resolution().conflicts()) {
            int step = graph.node(conflict.nodes().get(0)).step();
            List<String> ids = conflict.nodes().stream().map(i -> graph.node(i).featureId()).toList();
            lines.add("Feature order cycle in step " + report.stepName(step) + " between " + String.join(", ", ids)
                    + " (biomes: " + shortList(conflict.sources(), 6) + ")");
            for (Resolution.RemovedEdge removed : conflict.removed()) {
                lines.add("  removed: " + edgeText(graph, removed.edge()) + " (asked by " + shortList(removed.edge().sources(), 6) + ")"
                        + (removed.pinnedBroken() ? " [touches a pinned feature]" : ""));
            }
        }
        return lines;
    }

    public static String shortList(Collection<String> values, int max) {
        List<String> list = new ArrayList<>(values);
        if (list.size() <= max) {
            return String.join(", ", list);
        }
        return String.join(", ", list.subList(0, max)) + " and " + (list.size() - max) + " more";
    }

    private static String biomeList(Collection<String> sources) {
        return "asked by " + sources.size() + (sources.size() == 1 ? " biome: " : " biomes: ") + String.join(", ", sources);
    }

    private static String namespaceCounts(List<String> ids) {
        Map<String, Integer> counts = new TreeMap<>();
        ids.forEach(id -> counts.merge(namespace(id), 1, Integer::sum));
        List<String> parts = new ArrayList<>();
        counts.forEach((ns, count) -> parts.add(ns + " " + count));
        return String.join(", ", parts);
    }

    static String fingerprint(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }
}
