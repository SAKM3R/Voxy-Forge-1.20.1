package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraftforge.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.lwjgl.opengl.GL33.glBindSampler;

/**
 * Shader-mod bridge for Forge 1.20.1.
 *
 * On 1.20.1 Forge the Iris port is Oculus. Voxy's Iris render-pipeline integration (LOD terrain drawn
 * through the shader pack's gbuffers) targets modern Iris internals and is not part of this port, so
 * this class only talks to the stable Iris API ({@code net.irisshaders.iris.api.v0.IrisApi}, which
 * Oculus provides) via reflection - no compile-time dependency on Oculus.
 *
 * While a shader pack is in use Voxy does not draw LODs (see {@link VoxyRenderSystem#renderOpaque});
 * with shaders off, or without Oculus, everything works normally.
 */
public class IrisUtil {

    public record CapturedViewportParameters(ChunkRenderMatrices matrices, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.matrices, this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    public static final boolean IRIS_INSTALLED = isLoaded("oculus") || isLoaded("iris");
    //The Iris render pipeline integration is not ported to Oculus
    public static final boolean SHADER_SUPPORT = false;

    private static final Object API;
    private static final MethodHandle IS_SHADER_PACK_IN_USE;
    private static final MethodHandle IS_RENDERING_SHADOW_PASS;
    private static final MethodHandle GET_CONFIG;

    static {
        Object api = null;
        MethodHandle inUse = null, shadow = null, config = null;
        if (IRIS_INSTALLED) {
            try {
                var lookup = MethodHandles.publicLookup();
                Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = apiClass.getMethod("getInstance").invoke(null);
                inUse = lookup.findVirtual(apiClass, "isShaderPackInUse", MethodType.methodType(boolean.class));
                shadow = lookup.findVirtual(apiClass, "isRenderingShadowPass", MethodType.methodType(boolean.class));
                config = lookup.findVirtual(apiClass, "getConfig", MethodType.methodType(Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig")));
            } catch (Throwable t) {
                Logger.warn("Oculus/Iris is installed but its API could not be accessed; shader detection disabled", t);
                api = null;
            }
        }
        API = api;
        IS_SHADER_PACK_IN_USE = inUse;
        IS_RENDERING_SHADOW_PASS = shadow;
        GET_CONFIG = config;
    }

    private static boolean isLoaded(String modId) {
        var list = ModList.get();
        return list != null && list.isLoaded(modId);
    }

    public static boolean irisShadowActive() {
        if (API == null) return false;
        try {
            return (boolean) IS_RENDERING_SHADOW_PASS.invoke(API);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when a shader pack is active. Voxy's shader-pack pipeline is not available on this port,
     * so callers use this to stand down.
     */
    public static boolean shaderPackActive() {
        if (API == null) return false;
        try {
            return (boolean) IS_SHADER_PACK_IN_USE.invoke(API);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether Voxy's Iris render pipeline is in charge of LOD rendering. Never the case on this port.
     */
    public static boolean irisShaderPackEnabled() {
        return false;
    }

    public static void clearIrisSamplers() {
        if (!IRIS_INSTALLED) return;
        for (int i = 0; i < 16; i++) {
            glBindSampler(i, 0);
        }
    }

    public static void reload() {
        //Nothing to reload: the Iris pipeline integration is not part of this port
    }

    public static void disableIrisShaders() {
        if (API == null) return;
        try {
            Object config = GET_CONFIG.invoke(API);
            config.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, false);
        } catch (Throwable t) {
            Logger.error("Failed to disable shaders", t);
        }
    }
}
