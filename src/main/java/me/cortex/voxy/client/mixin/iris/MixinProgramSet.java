package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Final;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

@Mixin(value = ProgramSet.class, remap = false)
public class MixinProgramSet implements IGetVoxyPatchData {
    @Shadow @Final private PackDirectives packDirectives;
    @Unique IrisShaderPatch patchData;

    // Mixin 0.8.5 (Forge 1.20.1) cannot @Inject at an INVOKE inside a constructor, so the call is wrapped instead.
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;locateDirectives()V"))
    private void voxy$injectPatchMaker(ProgramSet instance, Operation<Void> original,
                                       @Local(argsOnly = true) AbsolutePackPath directory,
                                       @Local(argsOnly = true) Function<AbsolutePackPath, String> sourceProvider,
                                       @Local(argsOnly = true) ShaderPack pack) {
        if (VoxyConfig.CONFIG.isRenderingEnabled() && IrisUtil.SHADER_SUPPORT) {
            this.patchData = IrisShaderPatch.makePatch(pack, directory, sourceProvider);
        }
        original.call(instance);
        /*
        if (this.patchData != null) {
            //Inject directives from voxy
            DispatchingDirectiveHolder ddh = new DispatchingDirectiveHolder();
            this.packDirectives.acceptDirectivesFrom(ddh);
            CommentDirectiveParser.findDirective(this.patchData.getPatchSource(), CommentDirective.Type.RENDERTARGETS)
                    .map(dir->Arrays.stream(dir.getDirective().split(","))
                            .mapToInt(Integer::parseInt).toArray())
                    .ifPresent(ddh::processDirective);

        }
         */
    }


    @Override
    public IrisShaderPatch voxy$getPatchData() {
        return this.patchData;
    }
}
