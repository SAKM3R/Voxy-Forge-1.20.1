package me.cortex.voxy.client.compat;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.config.VoxyConfigMenu;
import me.cortex.voxy.client.core.util.IrisUtil;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;
import org.embeddedt.embeddium.api.render.clouds.ModifyCloudRenderingEvent;

/**
 * Hooks into Embeddium's own event API (it replaces the Sodium 0.8 config API and cloud mixin used on NeoForge).
 */
public final class EmbeddiumCompat {
    private EmbeddiumCompat() {}

    public static void init() {
        ModifyCloudRenderingEvent.BUS.addListener(EmbeddiumCompat::onModifyCloudRendering);
        OptionGUIConstructionEvent.BUS.addListener(event -> {
            for (var page : VoxyConfigMenu.createPages()) {
                event.addPage(page);
            }
        });
    }

    /**
     * Embeddium's cloud renderer asks this event for its render distance (in chunks).
     */
    private static void onModifyCloudRendering(ModifyCloudRenderingEvent event) {
        int vanillaDistance = event.getCloudRenderDistance();
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || IrisUtil.shaderPackActive()) {
            return;
        }
        int chunks = VoxyConfig.CONFIG.adaptCloudDistance
                ? Math.round(VoxyConfig.CONFIG.sectionRenderDistance * 32.0f)
                : VoxyConfig.CONFIG.cloudDistance;
        if (chunks < 1) {
            return;
        }
        chunks = Math.min(chunks, VoxyConfig.MAX_CLOUD_DISTANCE);
        event.setCloudRenderDistance(VoxyConfig.CONFIG.adaptCloudDistance ? Math.max(vanillaDistance, chunks) : chunks);
    }
}
