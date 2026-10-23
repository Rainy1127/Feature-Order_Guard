package dev.featureorderguard;

import com.mojang.brigadier.context.CommandContext;
import dev.featureorderguard.core.FeatureGraph;
import dev.featureorderguard.core.ReportFormatter;
import dev.featureorderguard.core.Resolution;
import dev.featureorderguard.core.SourceReport;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /featureorderguard report} (game masters): a short summary in chat. The full text is in the report file.
 * Every text has an English fallback, so players without the mod on their client still read real sentences.
 */
public final class ReportCommand {
    private static final int MAX_LINES = 8;

    private ReportCommand() {
    }

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("featureorderguard")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("report").executes(ReportCommand::report)));
    }

    private static int report(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<SourceReport> reports = ReportFormatter.sorted(ReportStore.snapshot());
        Strategy strategy = ReportStore.strategy();

        source.sendSuccess(() -> Component.translatableWithFallback("feature_order_guard.command.header",
                "Feature Order Guard - strategy %s", strategy.name()).withStyle(ChatFormatting.AQUA), false);

        if (reports.isEmpty()) {
            source.sendSuccess(() -> Component.translatableWithFallback("feature_order_guard.command.none",
                    "No feature order cycles in this session. Nothing was changed.").withStyle(ChatFormatting.GREEN), false);
            return 0;
        }

        int cycles = ReportFormatter.cycleCount(reports);
        int removed = ReportFormatter.removedCount(reports);
        source.sendSuccess(() -> Component.translatableWithFallback("feature_order_guard.command.summary",
                "%s cycle(s) in %s biome source(s), %s ordering edge(s) removed.", cycles, reports.size(), removed)
                .withStyle(ChatFormatting.GOLD), false);

        int shown = 0;
        outer:
        for (SourceReport report : reports) {
            FeatureGraph graph = report.graph();
            for (Resolution.RemovedEdge edge : report.resolution().removedEdges()) {
                if (shown == MAX_LINES) {
                    break outer;
                }
                String text = " - " + ReportFormatter.edgeText(graph, edge.edge()) + "  ("
                        + ReportFormatter.shortList(edge.edge().sources(), 3) + ")";
                source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
                shown++;
            }
        }
        if (removed > shown) {
            int more = removed - shown;
            source.sendSuccess(() -> Component.translatableWithFallback("feature_order_guard.command.more",
                    "... and %s more", more).withStyle(ChatFormatting.DARK_GRAY), false);
        }
        String file = "logs/" + ReportStore.REPORT_FILE_NAME;
        source.sendSuccess(() -> Component.translatableWithFallback("feature_order_guard.command.file",
                "Full report: %s", file).withStyle(ChatFormatting.AQUA), false);
        return cycles;
    }
}
