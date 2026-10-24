package me.cortex.voxy;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Main mod class for Voxy on NeoForge.
 *
 * Handles config-screen setup; actual initialization happens via mixins (MixinRenderSystem).
 *
 * Voxy has a single source of truth for its settings: the JSON file voxy-config.json
 * ({@link me.cortex.voxy.client.config.VoxyConfig}). It is edited through the Voxy page that
 * {@link me.cortex.voxy.client.config.VoxyConfigMenu} adds to Sodium's Video Settings via the native
 * Sodium config API ({@code @ConfigEntryPointForge}) - the same page Reese's Sodium Options renders.
 * Sodium is a required dependency, so that page is always available.
 *
 * We deliberately do NOT register a NeoForge {@code ModConfigSpec}/.toml here. Doing so created a second,
 * parallel copy of every setting, and the one-way sync that kept it in step overwrote the user's real
 * (JSON) settings with the .toml's defaults on every {@code ModConfigEvent.Loading} - so nothing appeared
 * to save across a restart. Instead, the "Config" button on Voxy's row in the Mods list simply opens the
 * Sodium Video Settings screen, jumping straight to the Voxy page.
 */
@Mod("voxy")
public class Voxy {

    public Voxy(IEventBus modEventBus, ModContainer container) {
        // Only wire up the client config screen on the client side.
        if (FMLLoader.getDist() == Dist.CLIENT) {
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (modContainer, parent) -> openVoxyConfigScreen(parent));
        }
    }

    // Opens Sodium's Video Settings on Voxy's page (added by VoxyConfigMenu). Falls back to the plain
    // Video Settings screen if the Voxy page isn't registered (e.g. Voxy disabled itself because the GL
    // capabilities it needs are missing), so the button never dead-ends.
    private static Screen openVoxyConfigScreen(Screen parent) {
        if (VoxyCommon.isAvailable() && ConfigManager.CONFIG != null) {
            for (var mod : ConfigManager.CONFIG.getModOptions()) {
                if (mod.configId().equals("voxy")
                        && !mod.pages().isEmpty()
                        && mod.pages().get(0) instanceof OptionPage page) {
                    return VideoSettingsScreen.createScreen(parent, page);
                }
            }
        }
        return VideoSettingsScreen.createScreen(parent);
    }
}
