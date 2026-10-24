package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.CenterLodRegion;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.fml.ModList;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Unique
    private static final boolean BOBBY_INSTALLED = ModList.get().isLoaded("bobby");

    @Shadow @Final private ClientLevel level;

    @Shadow @Final private ChunkBuilder builder;

    // Sodium 0.8: Constructor signature is (ClientLevel, int, SortBehavior, CommandList)
    // (the SortBehavior parameter, dropped in 0.6.x, was reintroduced in the modern Sodium 0.8 backport)
    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetChunkTracker(ClientLevel level, int renderDistance, SortBehavior sortBehavior, CommandList commandList, CallbackInfo ci) {
        if (level.levelRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(level.levelRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.reset();
            }
        }
        // MC 1.21.1: Use getMinBuildHeight() instead of getMinY()
        this.bottomSectionY = ((net.minecraft.world.level.Level)this.level).getMinBuildHeight()>>4;
    }

    // Render the OUTERMOST ~1-chunk ring of the vanilla render distance as Voxy LOD instead of vanilla.
    // The outermost ring is the newest, actively-streaming border chunks, and their water rendered wrong
    // right at the vanilla<->LOD boundary (weird colour without shaders, blowing out white with shaders).
    // By shrinking Sodium's section-visibility search distance by one chunk, those border sections are
    // never visited -> never built -> never drawn as vanilla, AND (because the LOD-occlusion bound is
    // keyed on BUILT sections via voxy$updateOnUpload) they never mask the LOD. So Voxy LOD fills that
    // ring cleanly, with no vanilla there to render wrong or fight the LOD. Only the visibility BFS is
    // affected; chunk loading/ingest still happen at full distance.
    @ModifyArg(
        method = "createTerrainRenderList",
        at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller;findVisible(Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/RenderSectionVisitor;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;FZI)V"),
        index = 2
    )
    private float voxy$cullOutermostVanillaRing(float searchDistance) {
        float culled = Math.max(searchDistance - 16.0f, 16.0f);
        // Publish the EXACT vanilla cull edge to the LOD-occlusion circle (ChunkBoundRenderer) so the
        // two align perfectly. A fixed -16 on the circle under-shoots this because searchDistance carries
        // a buffer beyond renderDistance*16 - that leftover sliver was the faint residual water bug.
        ChunkBoundRenderer.VANILLA_CULL_DISTANCE = culled;
        return culled;
    }

    // Central-band LOD culling (the FPS win): before Sodium walks the visibility graph, compute the
    // band-edge planes from the CURRENT camera view-projection. We do this at render-list build time,
    // which Sodium re-runs every frame the camera rotates, so the planes stay current. Skipped during
    // Iris' shadow pass (shadow casters must stay full-FOV).
    @Inject(method = "createTerrainRenderList", at = @At("HEAD"))
    private void voxy$prepareBandCull(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfoReturnable<Boolean> cir) {
        CenterLodRegion.beginBandCull();
        if (IrisUtil.irisShadowActive()) {
            return;
        }
        int[] scissor = CenterLodRegion.computeScissor();
        if (scissor == null) {
            return;//Slider at 100% (or band covers the window) -> nothing to cull
        }
        int fbW = Minecraft.getInstance().getWindow().getWidth();
        if (fbW <= 0) {
            return;
        }
        double bandHalfNdc = (double) scissor[2] / fbW;//band width px / window width px
        var cam = viewport.getTransform();
        // Vanilla camera view-projection (maps camera-relative world coords -> clip). Only the x/w rows
        // are used, which the projection's reverse-Z/far-plane tweaks never touch.
        Matrix4f vp = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix());
        CenterLodRegion.setupBandCull(cam.x, cam.y, cam.z, vp, bandHalfNdc);
    }

    // Wrap the render-list sink so sections projecting entirely into the wings are never added -> Sodium
    // issues no draw call and does no vertex work for them. Traversal is untouched (we wrap the visitor,
    // not the culler), so connectivity/occlusion is unchanged - no holes inside the band.
    @ModifyArg(
        method = "createTerrainRenderList",
        at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller;findVisible(Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/RenderSectionVisitor;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;FZI)V"),
        index = 0
    )
    private RenderSectionVisitor voxy$bandCullVisitor(RenderSectionVisitor original) {
        if (!CenterLodRegion.isBandCullActive()) {
            return original;
        }
        return section -> {
            if (CenterLodRegion.isSectionInWings(section.getChunkX() << 4, section.getChunkY() << 4, section.getChunkZ() << 4)) {
                return;//Drop wing section from the render list
            }
            original.visit(section);
        };
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void injectIngest(int x, int z, CallbackInfo ci) {
        //TODO: Am not quite sure if this is right
        if (VoxyConfig.CONFIG.ingestEnabled && !BOBBY_INSTALLED) {
            var cccm = (ICheekyClientChunkCache)this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.voxy$cheekyGetChunk(x, z);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }


    // Sodium 0.8: when a section is disposed (chunk unloads / leaves render distance), explicitly
    // clear it from the LOD-occlusion bound. The build-state redirect (voxy$updateOnUpload) misses
    // this in 0.8 because RenderSection.setInfo() returns false during disposal, which made the
    // section keep masking Voxy's LOD - so the area went invisible for a moment until something
    // else cleared it. Keying matches voxy$updateOnUpload so add/remove line up.
    @Inject(method = "onSectionRemoved", at = @At("HEAD"))
    private void voxy$clearBoundOnSectionRemoved(int x, int y, int z, CallbackInfo ci) {
        if (this.level.levelRenderer == null) {
            return;
        }
        var system = ((IGetVoxyRenderSystem)(this.level.levelRenderer)).getVoxyRenderSystem();
        if (system == null) {
            return;
        }
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (x+512)>>10;
            x -= sector<<10;
            y += 16+(256-32-sector*30);
        }
        // Disposal means Sodium has stopped drawing this section, so clear the mask immediately
        // (the delayed path would leave a brief see-through gap before the LOD shows).
        system.chunkBoundRenderer.removeSectionImmediate(SectionPos.asLong(x, y, z));
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (this.level.levelRenderer != null && VoxyConfig.CONFIG.ingestEnabled) {
            var cccm = this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }

    /*
    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$trackChunkRemove(int x, int z, CallbackInfo ci) {
        if (this.level.worldRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(this.level.worldRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.removeSection(ChunkPos.toLong(x, z));
            }
        }
    }*/

    @Unique private long cachedChunkPos = -1;
    @Unique private int cachedChunkStatus;
    @Unique private int bottomSectionY;

    @Redirect(method = "updateSectionInfo", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)Z"))
    private boolean voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
        boolean wasBuilt = instance.getFlags()!=0;
        int flags = instance.getFlags();
        if (!instance.setInfo(info)) {
            return false;
        }
        if (wasBuilt == (instance.getFlags()!=0)) {//Only want to do stuff on change
            return true;
        }

        flags |= instance.getFlags();
        if (flags == 0)//Only process things with stuff
            return true;

        VoxyRenderSystem system = ((IGetVoxyRenderSystem)(this.level.levelRenderer)).getVoxyRenderSystem();
        if (system == null) {
            return true;
        }
        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();

        if (wasBuilt && VoxyConfig.CONFIG.ingestEnabled) {
            var tracker = ((AccessorChunkTracker)ChunkTrackerHolder.get(this.level)).getChunkStatus();
            //in theory the cache value could be wrong but is so soso unlikely and at worst means we either duplicate ingest a chunk
            // which... could be bad ;-; or we dont ingest atall which is ok!
            long key = ChunkPos.asLong(x, z);
            if (key != this.cachedChunkPos) {
                this.cachedChunkPos = key;
                this.cachedChunkStatus = tracker.getOrDefault(key, 0);
            }
            if (this.cachedChunkStatus == 3) {//If this chunk still has surrounding chunks
                var section = this.level.getChunk(x,z).getSection(y-this.bottomSectionY);
                var lp = this.level.getLightEngine();

                var csp = SectionPos.of(x,y,z);
                var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
                var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);

                //Note: we dont do this check and just blindly ingest, it shouldbe ok :tm:
                //if (blp != null || slp != null)
                    VoxelIngestService.rawIngest(system.getEngine(), section, x,y,z, blp==null?null:blp.copy(), slp==null?null:slp.copy());
            }
        }

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (x+512)>>10;
            x-=sector<<10;
            y+=16+(256-32-sector*30);
        }
        long pos = SectionPos.asLong(x,y,z);
        if (wasBuilt) {//Remove
            //TODO: on chunk remove do ingest if is surrounded by built chunks (or when the tracker says is ok)

            system.chunkBoundRenderer.removeSection(pos);
        } else {//Add
            system.chunkBoundRenderer.addSection(pos);
        }
        return true;
    }
}
