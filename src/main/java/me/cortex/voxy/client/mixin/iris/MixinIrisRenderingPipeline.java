package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline implements IGetVoxyPatchData, IGetIrisVoxyPipelineData {
    @Shadow @Final private CustomUniforms customUniforms;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;
    @Unique IrisShaderPatch patchData;
    @Unique
    IrisVoxyRenderPipelineData pipeline;

    // Mixin 0.8.5 (Forge 1.20.1) cannot @Inject at an INVOKE inside a constructor, so the calls are wrapped instead.
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/transform/ShaderPrinter;resetPrintState()V"))
    private void voxy$injectPatchDataStore(Operation<Void> original, @Local(argsOnly = true) ProgramSet programSet) {
        original.call();
        if (IrisUtil.SHADER_SUPPORT) {
            this.patchData = ((IGetVoxyPatchData) programSet).voxy$getPatchData();
        }
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;createSetupComputes([Lnet/irisshaders/iris/shaderpack/programs/ComputeSource;Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;Lnet/irisshaders/iris/shaderpack/texture/TextureStage;)[Lnet/irisshaders/iris/gl/program/ComputeProgram;"))
    private ComputeProgram[] voxy$injectPipeline(IrisRenderingPipeline instance, ComputeSource[] compute, ProgramSet programSet, TextureStage stage, Operation<ComputeProgram[]> original) {
        if (this.patchData != null) {
            this.pipeline = IrisVoxyRenderPipelineData.buildPipeline((IrisRenderingPipeline)(Object)this, this.patchData, this.customUniforms, this.shaderStorageBufferHolder);
        }
        return original.call(instance, compute, programSet, stage);
    }

    // Viewport setup happens once per frame at renderLevel HEAD (MixinLevelRenderer), before Iris uploads
    // its per-frame uniforms, so it is intentionally NOT done here (doing it here as well would update the
    // viewport twice per frame and double-advance frameId, and left the vx* uniforms a frame stale).

    @Override
    public IrisShaderPatch voxy$getPatchData() {
        return this.patchData;
    }

    @Override
    public IrisVoxyRenderPipelineData voxy$getPipelineData() {
        return this.pipeline;
    }
}
