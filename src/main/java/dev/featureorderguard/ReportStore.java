package dev.featureorderguard;

import dev.featureorderguard.core.ReportFormatter;
import dev.featureorderguard.core.SourceReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Keeps the reports of the current server session (one per biome source that needed fixing), logs them and
 * rewrites {@code logs/feature_order_guard-report.txt}. Thread safe; nothing here runs per tick.
 */
public final class ReportStore {
    public static final String REPORT_FILE_NAME = "feature_order_guard-report.txt";

    private static final List<SourceReport> REPORTS = new ArrayList<>();
    private static final Set<String> SIGNATURES = new HashSet<>();
    private static Strategy lastStrategy = Strategy.RESOLVE;

    private ReportStore() {
    }

    static synchronized void record(SourceReport report, Strategy strategy) {
        lastStrategy = strategy;
        if (!SIGNATURES.add(report.signature())) {
            return; // the same biome source was sorted again (NeoForge re-sorts after biome modifiers)
        }
        REPORTS.add(report);
        FeatureOrderGuard.LOGGER.warn("Feature Order Guard: {} feature order cycle(s) found in a biome source with {} biomes ({}).",
                report.resolution().conflicts().size(), report.sourceIds().size(),
                strategy == Strategy.RESOLVE ? "resolved, loading continues" : "not changed, strategy " + strategy);
        for (String line : ReportFormatter.logLines(report)) {
            FeatureOrderGuard.LOGGER.warn(line);
        }
        writeFile();
    }

    public static synchronized List<SourceReport> snapshot() {
        return List.copyOf(REPORTS);
    }

    public static synchronized Strategy strategy() {
        return REPORTS.isEmpty() ? GuardConfig.strategy() : lastStrategy;
    }

    public static Path reportPath() {
        return FMLPaths.GAMEDIR.get().resolve("logs").resolve(REPORT_FILE_NAME);
    }

    private static void writeFile() {
        if (!GuardConfig.writeReportFile()) {
            return;
        }
        String text = ReportFormatter.fullReport(REPORTS, strategy().name(), FeatureOrderGuard.version());
        Path path = reportPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
            FeatureOrderGuard.LOGGER.info("Feature Order Guard report written to {}", path);
        } catch (IOException | RuntimeException e) {
            FeatureOrderGuard.LOGGER.error("Could not write {}", path, e);
        }
    }

    /** Also writes a "no cycles" report, so an old report from a previous session never lingers. */
    static synchronized void onServerStarted(ServerStartedEvent event) {
        writeFile();
    }

    /** A new server session (another world in single player) starts with a clean list. */
    static synchronized void onServerStopped(ServerStoppedEvent event) {
        REPORTS.clear();
        SIGNATURES.clear();
    }
}
