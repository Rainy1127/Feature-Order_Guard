package dev.featureorderguard;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Feature Order Guard: turns the "Feature order cycle found" worldgen crash into a fixed order plus a report.
 * Server side only (in single player the integrated server does the work). No blocks, items, packets or tickers.
 */
@Mod(FeatureOrderGuard.MODID)
public final class FeatureOrderGuard {
    public static final String MODID = "feature_order_guard";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static String version = "1.0.0";

    public FeatureOrderGuard(IEventBus modBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.STARTUP, GuardConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(ReportCommand::register);
        NeoForge.EVENT_BUS.addListener(ReportStore::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ReportStore::onServerStopped);
    }

    public static String version() {
        return version;
    }
}
