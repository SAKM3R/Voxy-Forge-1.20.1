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
        mc.options.renderDistance().set(4);
        mc.options.simulationDistance().set(5);
        mc.options.pauseOnLostFocus = false;
        mc.options.onboardAccessibility = false;
        LOG.info("VOXY_SMOKE creating world");
        mc.tell(() -> mc.createWorldOpenFlows().createFreshLevel("voxy-smoke",
                new LevelSettings("voxy-smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(12345L, true, false),
                WorldPresets::createNormalWorldDimensions));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        maybeStart();
        if (System.nanoTime() - startNanos > 12L * 60 * 1_000_000_000L) {
            LOG.error("VOXY_SMOKE_RESULT timeout");
            mc.stop();
            return;
        }
        if (mc.level == null || mc.player == null) return;
        ticksInWorld++;
        if (ticksInWorld == 20) {
            mc.player.getAbilities().flying = true;
            mc.player.setPos(mc.player.getX(), mc.player.getY() + 25, mc.player.getZ());
            mc.player.setXRot(15);
        }
        if (ticksInWorld == 1500) {
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "voxy-smoke-" + ticksInWorld + ".png", mc.getMainRenderTarget(), msg -> LOG.info("VOXY_SMOKE screenshot: {}", msg.getString()));
        }
        if (ticksInWorld % 100 == 0) {
            report("progress");
        }
        if (ticksInWorld == 1550) {
            report("final");
            LOG.info("VOXY_SMOKE_RESULT done");
            mc.stop();
        }
    }

    private static void report(String stage) {
        var mc = Minecraft.getInstance();
        var vrs = ((IGetVoxyRenderSystem) mc.levelRenderer).getVoxyRenderSystem();
        LOG.info("VOXY_SMOKE {} ticks={} available={} instance={} renderSystem={} compute={} indirect={} subgroup={}",
                stage, ticksInWorld, VoxyCommon.isAvailable(), VoxyCommon.getInstance() != null, vrs != null,
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
