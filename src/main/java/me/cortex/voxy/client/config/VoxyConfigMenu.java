package me.cortex.voxy.client.config;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.embeddedt.embeddium.client.gui.options.OptionIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Voxy's pages in Embeddium's Video Settings screen (added through Embeddium's OptionGUIConstructionEvent,
 * see {@link me.cortex.voxy.client.compat.EmbeddiumCompat}). Embeddium's options screen does not scroll,
 * so the settings are split over three pages: Voxy, Voxy Fog and Voxy Extra.
 *
 * Values are written to {@link VoxyConfig} when the user presses Apply/Done, then saved to voxy-config.json.
 */
public final class VoxyConfigMenu {
    private VoxyConfigMenu() {}

    private static final OptionStorage<VoxyConfig> STORAGE = new OptionStorage<>() {
        @Override
        public VoxyConfig getData() {
            return VoxyConfig.CONFIG;
        }

        @Override
        public void save() {
            VoxyConfig.CONFIG.save();
        }
    };

    private static int idCounter = 0;

    public static List<OptionPage> createPages() {
        if (!VoxyCommon.isAvailable()) return List.of();//Dont even register the config if its not available
        List<OptionPage> pages = new ArrayList<>();
        pages.add(mainPage());
        pages.add(fogPage());
        pages.add(extraPage());
        return pages;
    }

    private static BooleanSupplier enabled() {
        return () -> VoxyConfig.CONFIG.enabled;
    }

    private static BooleanSupplier renderingEnabled() {
        return () -> VoxyConfig.CONFIG.enabled && VoxyConfig.CONFIG.enableRendering;
    }

    private static OptionImpl<VoxyConfig, Boolean> bool(String key, BiConsumer<VoxyConfig, Boolean> setter, Function<VoxyConfig, Boolean> getter, BooleanSupplier enabled, OptionFlag... flags) {
        var b = OptionImpl.createBuilder(boolean.class, STORAGE)
                .setId(OptionIdentifier.create("voxy", "option_" + key.replace('.', '_'), Boolean.class))
                .setName(Component.translatable("voxy.config." + key))
                .setTooltip(Component.translatable("voxy.config." + key + ".tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding(setter, getter)
                .setFlags(flags);
        if (enabled != null) b.setEnabledPredicate(enabled);
        return b.build();
    }

    private static OptionImpl<VoxyConfig, Integer> slider(String key, int min, int max, int step, ControlValueFormatter formatter, BiConsumer<VoxyConfig, Integer> setter, Function<VoxyConfig, Integer> getter, BooleanSupplier enabled, OptionFlag... flags) {
        var b = OptionImpl.createBuilder(int.class, STORAGE)
                .setId(OptionIdentifier.create("voxy", "option_" + key.replace('.', '_'), Integer.class))
                .setName(Component.translatable("voxy.config." + key))
                .setTooltip(Component.translatable("voxy.config." + key + ".tooltip"))
                .setControl(opt -> new SliderControl(opt, min, max, step, formatter))
                .setBinding(setter, getter)
                .setFlags(flags);
        if (enabled != null) b.setEnabledPredicate(enabled);
        return b.build();
    }

    private static OptionPage page(String path, Component name, OptionGroup... groups) {
        return new OptionPage(OptionIdentifier.create("voxy", path), name, ImmutableList.copyOf(groups));
    }

    private static OptionPage mainPage() {
        return page("general", Component.literal("Voxy"),
                OptionGroup.createBuilder()
                        .add(bool("general.enabled", (s, v) -> {
                            if (s.enabled == v) return;
                            s.enabled = v;
                            var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                            if (v) {
                                if (VoxyClientInstance.isInGame) {
                                    if (VoxyCommon.getInstance() == null) {
                                        VoxyCommon.createInstance();
                                    }
                                    if (vrsh != null && s.enableRendering && vrsh.getVoxyRenderSystem() == null) {
                                        vrsh.createRenderer();
                                    }
                                }
                            } else {
                                if (vrsh != null) {
                                    vrsh.shutdownRenderer();
                                }
                                VoxyCommon.shutdownInstance();
                            }
                        }, s -> s.enabled, null, OptionFlag.REQUIRES_RENDERER_RELOAD))
                        .build(),
                OptionGroup.createBuilder()
                        .add(slider("general.serviceThreads", 1, Math.max(2, CpuLayout.getCoreCount()), 1, ControlValueFormatter.number(),
                                (s, v) -> {
                                    s.serviceThreads = v;
                                    var instance = VoxyCommon.getInstance();
                                    if (instance != null) {
                                        instance.updateDedicatedThreads();
                                    }
                                }, s -> s.serviceThreads, enabled()))
                        .add(bool("general.useSodiumBuilder", (s, v) -> {
                            s.dontUseSodiumBuilderThreads = !v;
                            var instance = VoxyCommon.getInstance();
                            if (instance != null) {
                                instance.updateDedicatedThreads();
                            }
                        }, s -> !s.dontUseSodiumBuilderThreads, enabled(), OptionFlag.REQUIRES_RENDERER_RELOAD))
                        .add(bool("general.ingest", (s, v) -> s.ingestEnabled = v, s -> s.ingestEnabled, enabled()))
                        .build(),
                OptionGroup.createBuilder()
                        .add(bool("general.rendering", (s, v) -> {
                            if (s.enableRendering == v) return;
                            s.enableRendering = v;
                            var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                            if (vrsh != null) {
                                if (v) {
                                    if (vrsh.getVoxyRenderSystem() == null) vrsh.createRenderer();
                                } else {
                                    vrsh.shutdownRenderer();
                                }
                            }
                        }, s -> s.enableRendering, enabled(), OptionFlag.REQUIRES_RENDERER_RELOAD))
                        .add(slider("general.subDivisionSize", 0, SUBDIV_IN_MAX, 1, v -> Component.literal(Integer.toString(Math.round(ln2subDiv(v)))),
                                (s, v) -> s.subDivisionSize = ln2subDiv(v), s -> subDiv2ln(s.subDivisionSize), renderingEnabled()))
                        .add(slider("general.renderDistance", 2, 64, 1, v -> Component.literal(Integer.toString(v * 32)),//Top level rd == 32 chunks
                                (s, v) -> {
                                    s.sectionRenderDistance = v;
                                    var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                    if (vrsh != null) {
                                        var vrs = vrsh.getVoxyRenderSystem();
                                        if (vrs != null) {
                                            vrs.setRenderDistance(v);
                                        }
                                    }
                                }, s -> s.sectionRenderDistance, renderingEnabled()))
                        .add(bool("general.render_statistics", (s, v) -> RenderStatistics.enabled = v, s -> RenderStatistics.enabled, renderingEnabled(), OptionFlag.REQUIRES_RENDERER_RELOAD))
                        .build()
        );
    }

    private static OptionPage fogPage() {
        // All fog values are read per frame in NormalRenderPipeline.finish, so none need a reload.
        return page("fog", Component.literal("Voxy Fog"),
                OptionGroup.createBuilder()
                        .add(bool("general.environmental_fog", (s, v) -> s.useEnvironmentalFog = v, s -> s.useEnvironmentalFog, renderingEnabled()))
                        .add(slider("general.skyFogDistance", 16, 512, 8, v -> Component.literal(v + " chunks"),
                                (s, v) -> s.skyFogDistance = v, s -> s.skyFogDistance, renderingEnabled()))
                        .add(slider("general.fogIntensity", 0, 100, 5, ControlValueFormatter.percentage(),
                                (s, v) -> s.fogIntensity = v / 100.0f, s -> Math.round(s.fogIntensity * 100), renderingEnabled()))
                        .add(slider("general.fogDensity", 0, 100, 5, ControlValueFormatter.percentage(),
                                (s, v) -> s.fogDensity = v / 100.0f, s -> Math.round(s.fogDensity * 100), renderingEnabled()))
                        .add(slider("general.fogDistancePercent", 5, 200, 5, ControlValueFormatter.percentage(),
                                (s, v) -> s.fogDistancePercent = v, s -> s.fogDistancePercent, renderingEnabled()))
                        .add(slider("general.fogStartPercent", 0, 95, 5, ControlValueFormatter.percentage(),
                                (s, v) -> s.fogStartPercent = v, s -> s.fogStartPercent, renderingEnabled()))
                        .build(),
                OptionGroup.createBuilder()
                        .add(bool("general.caveFog", (s, v) -> s.caveFogEnabled = v, s -> s.caveFogEnabled, renderingEnabled()))
                        .add(slider("general.caveFogDistance", 8, 128, 4, v -> Component.literal(v + " blocks"),
                                (s, v) -> s.caveFogDistance = v, s -> s.caveFogDistance, renderingEnabled()))
                        .build(),
                OptionGroup.createBuilder()
                        .add(bool("general.adaptCloudDistance", (s, v) -> s.adaptCloudDistance = v, s -> s.adaptCloudDistance, renderingEnabled()))
                        .add(slider("general.cloudDistance", 0, VoxyConfig.MAX_CLOUD_DISTANCE, 8, v -> v == 0 ? Component.literal("Vanilla") : Component.literal(v + " chunks"),
                                (s, v) -> s.cloudDistance = v, s -> s.cloudDistance, renderingEnabled()))
                        .build()
        );
    }

    private static OptionPage extraPage() {
        BooleanSupplier genEnabled = () -> VoxyConfig.CONFIG.enabled && VoxyConfig.CONFIG.ingestEnabled;
        return page("extra", Component.literal("Voxy Extra"),
                OptionGroup.createBuilder()
                        .add(bool("distantgen.enabled", (s, v) -> s.distantGenEnabled = v, s -> s.distantGenEnabled, genEnabled))
                        .add(slider("distantgen.radius", 16, 1024, 16, v -> Component.literal(v + " chunks"),
                                (s, v) -> s.distantGenRadius = v, s -> s.distantGenRadius, genEnabled))
                        .add(slider("distantgen.maxInFlight", 1, 128, 1, ControlValueFormatter.number(),
                                (s, v) -> s.distantGenMaxInFlight = v, s -> s.distantGenMaxInFlight, genEnabled))
                        .add(slider("distantgen.maxMspt", 10, 50, 1, v -> Component.literal(v + " ms"),
                                (s, v) -> s.distantGenMaxMspt = v, s -> s.distantGenMaxMspt, genEnabled))
                        .build(),
                OptionGroup.createBuilder()
                        // Central-band LOD culling: width (% of monitor width) of the centre column that stays
                        // vanilla; the side wings become LOD. 100% = off. Read per frame, so no reload needed.
                        .add(slider("advanced.center_lod_width", 30, 100, 1, ControlValueFormatter.percentage(),
                                (s, v) -> s.lodCenterWidthPct = v, s -> s.lodCenterWidthPct, renderingEnabled()))
                        .add(slider("advanced.lod_boundary_buffer", 0, 4, 1, v -> v == 0 ? Component.literal("Exact") : Component.literal(v + " blocks"),
                                (s, v) -> s.lodBoundaryBuffer = v, s -> s.lodBoundaryBuffer, renderingEnabled()))
                        // Uploaded as a per-frame uniform (MDICSectionRenderer), so it applies live.
                        .add(slider("advanced.earth_curve", 0, 5000, 50, v -> v == 0 ? Component.literal("Disabled") : (v < 50 ? Component.literal("50 (min)") : Component.literal(v + "x")),
                                (s, v) -> s.earthCurveRatio = (v > 0 && v < 50) ? 50 : v, s -> s.earthCurveRatio, renderingEnabled()))
                        .build()
        );
    }

    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX/SUBDIV_MIN)/Math.log(2);

    //In range is 0->100
    //Out range is 28->256
    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN*Math.pow(2, SUBDIV_CONST*((double)in/SUBDIV_IN_MAX)));
    }

    //Out range is 0->100
    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double)in)/SUBDIV_MIN)/Math.log(2))/SUBDIV_CONST)*SUBDIV_IN_MAX);
    }
}
