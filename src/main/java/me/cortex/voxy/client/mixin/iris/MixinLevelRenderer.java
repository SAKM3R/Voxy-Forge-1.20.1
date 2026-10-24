package me.cortex.voxy.client.mixin.iris;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL11C.glViewport;

@Mixin(value = LevelRenderer.class, priority = 900)
public class MixinLevelRenderer {
    // MC 1.20.1: renderLevel(PoseStack, float, long, boolean, Camera, GameRenderer, LightTexture, Matrix4f)
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void voxy$injectIrisCompat(
            PoseStack poseStack,
            float partialTick,
            long finishNanoTime,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        if (IrisUtil.irisShaderPackEnabled()) {
            var renderer = ((IGetVoxyRenderSystem) this).getVoxyRenderSystem();
            if (renderer != null) {
                //Fix the viewport dims, iris leaves them wrong
                glViewport(0,0,Minecraft.getInstance().getMainRenderTarget().width, Minecraft.getInstance().getMainRenderTarget().height);

                var pos = camera.getPosition();
                IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = new IrisUtil.CapturedViewportParameters(new ChunkRenderMatrices(new Matrix4f(projectionMatrix), new Matrix4f(poseStack.last().pose())), pos.x, pos.y, pos.z);
                // Set the viewport up now, at the very start of the frame, BEFORE Iris uploads its
                // per-frame uniforms (vxProj/vxModelView/vxViewProj...), so they are not a frame stale.
                IrisUtil.CAPTURED_VIEWPORT_PARAMETERS.apply(renderer);
            }
        }
    }
}
