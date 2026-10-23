package dev.featureorderguard;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only entry point: just the config screen (Mods -> Feature Order Guard -> Config). */
@Mod(value = FeatureOrderGuard.MODID, dist = Dist.CLIENT)
public final class FeatureOrderGuardClient {
    public FeatureOrderGuardClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
