package dev.featureorderguard;

import com.electronwill.nightconfig.core.EnumGetMethod;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * STARTUP config ({@code config/feature_order_guard-startup.toml}). It is loaded while the mod is constructed,
 * long before any world or datapack exists, which is exactly when feature sorting needs it.
 * Changes need a game restart.
 */
public final class GuardConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<Strategy> STRATEGY = BUILDER
            .comment("What to do when worldgen mods/datapacks create a \"Feature order cycle found\" crash.",
                    "RESOLVE     - break the cycle with the fewest changes, keep loading, write a report (default)",
                    "REPORT_ONLY - write the report, then crash like vanilla",
                    "CRASH       - do nothing, pure vanilla behaviour",
                    "Case-insensitive. Needs a game restart.")
            .translation("feature_order_guard.configuration.strategy")
            .gameRestart()
            .defineEnum("strategy", Strategy.RESOLVE, EnumGetMethod.NAME_IGNORECASE);

    public static final ModConfigSpec.BooleanValue WRITE_REPORT_FILE = BUILDER
            .comment("Write logs/feature_order_guard-report.txt (the log always gets a short summary).")
            .translation("feature_order_guard.configuration.write_report_file")
            .define("write_report_file", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private GuardConfig() {
    }

    /** Safe read: falls back to the default if the config is somehow not loaded yet. */
    public static Strategy strategy() {
        try {
            Strategy value = STRATEGY.get();
            return value != null ? value : Strategy.RESOLVE;
        } catch (RuntimeException notLoaded) {
            return Strategy.RESOLVE;
        }
    }

    public static boolean writeReportFile() {
        try {
            return WRITE_REPORT_FILE.get();
        } catch (RuntimeException notLoaded) {
            return true;
        }
    }
}
