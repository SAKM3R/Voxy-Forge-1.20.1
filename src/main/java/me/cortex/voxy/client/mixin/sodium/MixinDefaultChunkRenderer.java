package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.CenterLodRegion;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
// MC 1.21.1 NeoForge: Iris shader integration excluded
// import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);
    }

    // True when a central-band scissor was applied for the current pass (vanilla terrain clipped to the
    // centre column) and still needs restoring before the LOD pass.
    @Unique private boolean voxy$bandScissor;

    // Sodium 0.8: render signature is (ChunkRenderMatrices, CommandList, ChunkRenderListIterable, TerrainRenderPass, CameraTransform, boolean)
    // (the trailing boolean, dropped in 0.6.x, was reintroduced in the modern Sodium 0.8 backport)
    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void cancelThingie(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass);
            this.doRender(matrices, renderPass, camera);
            super.end(renderPass);
            ci.cancel();
            return;
        }
        // Vanilla terrain WILL be drawn this pass: clip it to the central vanilla column so the LOD
        // takes the left/right wings. Skipped during Iris' shadow pass so shadow casting stays full-FOV.
        // Restored in injectRender (below) BEFORE the LOD pass, so LOD itself fills the whole screen.
        this.voxy$bandScissor = !IrisUtil.irisShadowActive()
                && CenterLodRegion.applyScissor(CenterLodRegion.computeScissor());
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
    private void injectRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci) {
        // Stop clipping to the band before the LOD pass runs (doRender -> renderOpaque), so the LOD fills
        // the wings. The LOD-occlusion mask re-applies the same band itself, keeping the two aligned.
        CenterLodRegion.restoreScissor(this.voxy$bandScissor);
        this.voxy$bandScissor = false;
        this.doRender(matrices, renderPass, camera);
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).getVoxyRenderSystem();
            if (renderer != null) {
                Viewport<?> viewport = null;
                // With an Iris shader pack active, use the viewport captured from renderLevel; otherwise
                // build one here. irisShaderPackEnabled() is false when Iris is absent.
                if (IrisUtil.irisShaderPackEnabled()) {
                    // The viewport is set up once per frame at renderLevel HEAD (MixinLevelRenderer), before
                    // Iris reads its per-frame uniforms - so its matrices are current (not a frame late, which
                    // previously made shadows on LOD swim during fast camera movement). Just read it here.
                    viewport = renderer.getViewport();
                } else {
                    viewport = renderer.setupViewport(matrices, camera.x, camera.y, camera.z);
                }
                renderer.renderOpaque(viewport);
            }
        }
    }
}
