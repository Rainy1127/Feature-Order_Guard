package dev.featureorderguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReportFormatterTest {
    private static final List<String> STEPS = List.of("raw_generation", "lakes", "underground_ores");

    private static SourceReport sample(boolean reversedSourceOrder) {
        var b = new FeatureGraphBuilder<String>(s -> s, s -> s, s -> false);
        if (reversedSourceOrder) {
            b.addSource("minecraft:forest", List.of(List.of(), List.of(), List.of("minecraft:ore_debris_small", "minecraft:ore_ancient_debris_large")));
            b.addSource("minecraft:plains", List.of(List.of(), List.of(), List.of("minecraft:ore_ancient_debris_large", "minecraft:ore_debris_small")));
        } else {
            b.addSource("minecraft:plains", List.of(List.of(), List.of(), List.of("minecraft:ore_ancient_debris_large", "minecraft:ore_debris_small")));
            b.addSource("minecraft:forest", List.of(List.of(), List.of(), List.of("minecraft:ore_debris_small", "minecraft:ore_ancient_debris_large")));
        }
        return new SourceReport(b.sourceIds(), b.graph(), CycleResolver.resolve(b.graph()), STEPS, false);
    }

    @Test
    void reportNamesFeaturesBiomesStepAndRemovedEdge() {
        String text = ReportFormatter.fullReport(List.of(sample(false)), "RESOLVE", "1.0.0");
        assertTrue(text.contains("step \"underground_ores\""), text);
        assertTrue(text.contains("minecraft:ore_ancient_debris_large -> minecraft:ore_debris_small"), text);
        assertTrue(text.contains("Removed (1):"), text);
        assertTrue(text.contains("minecraft:ore_debris_small -> minecraft:ore_ancient_debris_large   (biomes affected: 1)"), text);
        assertTrue(text.contains("Biomes involved: minecraft:forest, minecraft:plains"), text);
        assertTrue(text.contains("Fingerprint: "), text);
    }

    @Test
    void sameInputGivesSameReportAndFingerprint() {
        String a = ReportFormatter.fullReport(List.of(sample(false)), "RESOLVE", "1.0.0");
        String b = ReportFormatter.fullReport(List.of(sample(false)), "RESOLVE", "1.0.0");
        assertEquals(a, b);
        // The "Biome source: N biomes" header lists counts only, so reordering biomes keeps the text identical.
        String c = ReportFormatter.fullReport(List.of(sample(true)), "RESOLVE", "1.0.0");
        assertEquals(a, c);
    }

    @Test
    void emptyReportSaysNothingChanged() {
        String text = ReportFormatter.fullReport(List.of(), "RESOLVE", "1.0.0");
        assertTrue(text.contains("no feature order cycles"), text);
    }

    @Test
    void signatureIgnoresSourceOrder() {
        assertEquals(sample(false).signature(), sample(true).signature());
    }

    @Test
    void namespaces() {
        assertEquals("minecraft", ReportFormatter.namespace("minecraft:stone"));
        assertEquals("(inline)", ReportFormatter.namespace("inline#3"));
        assertEquals("a, b and 1 more", ReportFormatter.shortList(List.of("a", "b", "c"), 2));
        assertEquals(Set.of(), Set.of());
    }

    @Test
    void reportOrderDoesNotDependOnWhichSourceWasSortedFirst() {
        var other = new FeatureGraphBuilder<String>(s -> s, s -> s, s -> false);
        other.addSource("minecraft:nether_wastes", List.of(List.of("minecraft:x", "minecraft:y")));
        other.addSource("minecraft:soul_sand_valley", List.of(List.of("minecraft:y", "minecraft:x")));
        SourceReport nether = new SourceReport(other.sourceIds(), other.graph(), CycleResolver.resolve(other.graph()), STEPS, false);
        String a = ReportFormatter.fullReport(List.of(sample(false), nether), "RESOLVE", "1.0.0");
        String b = ReportFormatter.fullReport(List.of(nether, sample(false)), "RESOLVE", "1.0.0");
        assertEquals(a, b);
    }
}
