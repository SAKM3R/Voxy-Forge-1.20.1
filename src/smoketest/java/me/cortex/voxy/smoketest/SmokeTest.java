package me.cortex.voxy.smoketest;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only smoke test (never part of the mod jar): run with -Dvoxy.smoketest=true. Creates a fresh
 * world from the title screen, plays for a while and reports whether Voxy came up, then quits.
 */
@Mod.EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class SmokeTest {
    private static final Logger LOG = LoggerFactory.getLogger("VoxySmokeTest");
    private static final boolean ENABLED = Boolean.getBoolean("voxy.smoketest");
    private static boolean started = false;
    private static int ticksInWorld = 0;
    private static long startNanos = System.nanoTime();

    private static int idleTicks = 0;

    private static void maybeStart() {
        var mc = Minecraft.getInstance();
        if (started || mc.getOverlay() != null) return;
        if (++idleTicks % 100 == 0) LOG.info("VOXY_SMOKE waiting, screen={}", mc.screen);
        if (mc.screen instanceof net.minecraftforge.client.gui.LoadingErrorScreen les) {
            //Forge shows this for load warnings as well as errors; log what it holds, then continue
            for (var f : les.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    LOG.info("VOXY_SMOKE loading screen {} = {}", f.getName(), f.get(les));
                } catch (Throwable ignored) {}
            }
            mc.setScreen(new TitleScreen());
            return;
        }
        if (!(mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) return;
        started = true;
        mc.options.renderDistance().set(5);
        mc.options.simulationDistance().set(5);
        mc.options.pauseOnLostFocus = false;
        mc.options.onboardAccessibility = false;
        LOG.info("VOXY_SMOKE creating world");
        var rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        mc.tell(() -> mc.createWorldOpenFlows().createFreshLevel("voxy-smoke",
                new LevelSettings("voxy-smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, SceneTest.ENABLED ? rules : new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(12345L, !SceneTest.ENABLED, false),
                SceneTest.ENABLED
                        ? (access -> access.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions())
                        : WorldPresets::createNormalWorldDimensions));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        maybeStart();
        if (System.nanoTime() - startNanos > 20L * 60 * 1_000_000_000L) {
            LOG.error("VOXY_SMOKE_RESULT timeout");
            mc.stop();
            return;
        }
        if (mc.level == null || mc.player == null) return;
        ticksInWorld++;
        if (SceneTest.ENABLED) {
            if (ticksInWorld % 100 == 0) report("progress");
            SceneTest.tick(mc, ticksInWorld);
            return;
        }
        if (ticksInWorld == 20) {
            mc.player.getAbilities().flying = true;
            mc.player.setPos(mc.player.getX(), mc.player.getY() + 25, mc.player.getZ());
            mc.player.setXRot(15);
        }
        //Fly across the world so chunks stream in and out around the vanilla/LOD seam
        if (ticksInWorld > 100 && ticksInWorld <= 500 && ticksInWorld % 10 == 0) {
            double nx = mc.player.getX() + 8;
            int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) nx, (int) mc.player.getZ());
            mc.player.setPos(nx, Math.max(mc.player.getY(), ground + 30), mc.player.getZ());
        }
        if (ticksInWorld == 1150) {
            mc.player.setYRot(90);//Look back (-X)
        }
        if (ticksInWorld == 1302) {
            //Same view without Voxy: shows which parts of the frame are LOD
            me.cortex.voxy.client.config.VoxyConfig.CONFIG.enableRendering = false;
            ((IGetVoxyRenderSystem) mc.levelRenderer).shutdownRenderer();
        }
        if (ticksInWorld == 1340) {
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "voxy-smoke-1340-novoxy.png", mc.getMainRenderTarget(), msg -> {});
        }
        if (ticksInWorld == 1345) {
            me.cortex.voxy.client.config.VoxyConfig.CONFIG.enableRendering = true;
            ((IGetVoxyRenderSystem) mc.levelRenderer).createRenderer();
        }
        if (ticksInWorld == 1350) {
            mc.player.setYRot(-90);//Look ahead (+X)
        }
        if (ticksInWorld == 1300 || ticksInWorld == 1500) {
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "voxy-smoke-" + ticksInWorld + ".png", mc.getMainRenderTarget(), msg -> LOG.info("VOXY_SMOKE screenshot: {}", msg.getString()));
        }
        if (ticksInWorld % 100 == 0) {
            report("progress");
        }
        if (ticksInWorld == 1550) {
            report("final");
            if (!COLOUR_CHECK) {
                LOG.info("VOXY_SMOKE_RESULT done");
                mc.stop();
            }
        }
        if (COLOUR_CHECK && ticksInWorld > 1550) {
            colourCheck(mc, ticksInWorld - 1550);
        }
    }

    // Colour check: the same view drawn once by LODs (render distance 5) and once by real chunks (render
    // distance 12), looking over a beach at the sea and at the land, so LOD water and grass colours can be
    // compared against vanilla terrain pixel for pixel.
    private static final boolean COLOUR_CHECK = !Boolean.getBoolean("voxy.smoketest.nocolour");
    private static java.util.concurrent.CompletableFuture<double[]> SPOT;
    private static double[] spot;

    private static void colourCheck(Minecraft mc, int t) {
        var server = mc.getSingleplayerServer();
        if (t == 1) {
            var origin = mc.player.blockPosition();
            SPOT = server.submit(() -> {
                var level = server.overworld();
                var beach = level.findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.BEACH), origin, 6400, 32, 64);
                if (beach == null) return null;
                var bp = beach.getFirst();
                var sea = level.findClosestBiome3d(b -> b.is(net.minecraft.tags.BiomeTags.IS_OCEAN), bp, 640, 16, 64);
                double yaw = 0;
                if (sea != null) {
                    var sp = sea.getFirst();
                    yaw = Math.toDegrees(Math.atan2(-(sp.getX() - bp.getX()), sp.getZ() - bp.getZ()));
                }
                return new double[]{bp.getX() + 0.5, bp.getZ() + 0.5, yaw};
            });
        }
        if (t == 40) {
            spot = SPOT.getNow(null);
            LOG.info("VOXY_SMOKE colour spot {}", spot == null ? "none" : java.util.Arrays.toString(spot));
            if (spot == null) { LOG.info("VOXY_SMOKE_RESULT done"); mc.stop(); return; }
            mc.player.setPos(spot[0], 100, spot[1]);
        }
        if (spot == null) return;
        if (t > 40 && t < 1400) {
            // Hover 22 blocks above the beach, looking slightly down
            int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) spot[0], (int) spot[1]);
            if (ground > mc.level.getMinBuildHeight() + 1) mc.player.setPos(spot[0], ground + 22, spot[1]);
            mc.player.setXRot(12);
        }
        // LOD view: render distance 5, Voxy on (distant generation fills the LODs around the new spot)
        if (t == 700) shot(mc, (float) spot[2], "colour-lod-sea");
        if (t == 720) shot(mc, (float) spot[2] + 180, "colour-lod-land");
        if (t == 740) {
            me.cortex.voxy.client.config.VoxyConfig.CONFIG.enableRendering = false;
            ((IGetVoxyRenderSystem) mc.levelRenderer).shutdownRenderer();
            mc.options.renderDistance().set(12);
        }
        // Vanilla view of the same terrain: render distance 12, Voxy off
        if (t == 1300) shot(mc, (float) spot[2], "colour-vanilla-sea");
        if (t == 1320) shot(mc, (float) spot[2] + 180, "colour-vanilla-land");
        if (t == 1340) {
            LOG.info("VOXY_SMOKE_RESULT done");
            mc.stop();
        }
    }

    private static void shot(Minecraft mc, float yaw, String name) {
        mc.player.setYRot(yaw);
        mc.player.setYHeadRot(yaw);
        // grab a few frames later so the new direction has rendered (and TAA settled)
        pendingShot = name;
        pendingShotAt = ticksInWorld + 12;
    }

    static void grab(Minecraft mc, String name) {
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, "voxy-" + name + ".png", mc.getMainRenderTarget(), msg -> LOG.info("VOXY_SMOKE screenshot: {}", msg.getString()));
    }

    private static String pendingShot;
    private static int pendingShotAt;

    @SubscribeEvent
    public static void onClientTickShots(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || pendingShot == null) return;
        if (ticksInWorld >= pendingShotAt) {
            var mc = Minecraft.getInstance();
            var name = pendingShot;
            pendingShot = null;
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "voxy-" + name + ".png", mc.getMainRenderTarget(), msg -> LOG.info("VOXY_SMOKE screenshot: {}", msg.getString()));
        }
    }

    private static void report(String stage) {
        var mc = Minecraft.getInstance();
        var vrs = ((IGetVoxyRenderSystem) mc.levelRenderer).getVoxyRenderSystem();
        LOG.info("VOXY_SMOKE {} ticks={} shaderPack={} available={} instance={} renderSystem={} compute={} indirect={} subgroup={}",
                stage, ticksInWorld, me.cortex.voxy.client.core.util.IrisUtil.irisShaderPackEnabled(), VoxyCommon.isAvailable(), VoxyCommon.getInstance() != null, vrs != null,
                Capabilities.INSTANCE.compute, Capabilities.INSTANCE.indirectParameters, Capabilities.INSTANCE.subgroup);
        if (vrs != null) {
            var lines = new java.util.ArrayList<String>();
            try {
                vrs.addDebugInfo(lines);
            } catch (Throwable t) {
                lines.add("debug info failed: " + t);
            }
            for (var l : lines) LOG.info("VOXY_SMOKE debug: {}", l);
        }
    }
}
