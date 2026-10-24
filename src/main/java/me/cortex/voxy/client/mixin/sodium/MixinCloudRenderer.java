package me.cortex.voxy.client.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.core.util.CloudRenderContext;
import me.jellysquid.mods.sodium.client.render.immediate.CloudRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

// Ported from neo-voxy: the render wrap marks the frame section where Embeddium's cloud renderer
// runs its own fog setup, so MixinFogRenderer leaves cloud fog alone instead of pushing
// it to infinity (which would pop clouds in at full opacity at the fog wall).
@Mixin(value = CloudRenderer.class, remap = false, priority = 1100)
public class MixinCloudRenderer {
    @WrapMethod(method = "render")
    private void voxy$trackCloudRendering(ClientLevel world, LocalPlayer player, PoseStack matrices, Matrix4f projectionMatrix,
                                          float ticks, float tickDelta, double cameraX, double cameraY, double cameraZ,
                                          Operation<Void> original) {
        CloudRenderContext.begin();
        try {
            original.call(world, player, matrices, projectionMatrix, ticks, tickDelta, cameraX, cameraY, cameraZ);
        } finally {
            CloudRenderContext.end();
        }
    }
}
