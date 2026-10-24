package me.cortex.voxy;

import me.cortex.voxy.client.VoxyClient;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * Main mod class for Voxy on Forge 1.20.1.
 *
 * Actual initialization happens via mixins (MixinRenderSystem). Voxy has a single source of truth for its
 * settings: the JSON file voxy-config.json ({@link me.cortex.voxy.client.config.VoxyConfig}), edited through
 * the Voxy pages that {@link me.cortex.voxy.client.config.VoxyConfigMenu} adds to Embeddium's Video Settings.
 * The "Config" button on Voxy's row in the Mods list opens that screen.
 */
@Mod("voxy")
public class Voxy {

    public Voxy() {
        if (FMLLoader.getDist() == Dist.CLIENT) {
            ClientSetup.init();
        }
    }

    //Separate class so the dedicated server never loads client classes
    private static final class ClientSetup {
        static void init() {
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new SodiumOptionsGUI(parent)));
            VoxyClient.registerEvents();
        }
    }
}
