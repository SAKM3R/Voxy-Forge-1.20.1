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
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.fml.ModList;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkStatus;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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

    @Shadow @Final private ClientLevel world;

    @Shadow @Final private ChunkBuilder builder;

    // Embeddium 0.3.x: constructor is (ClientLevel, int, CommandList)
    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetChunkTracker(ClientLevel level, int renderDistance, CommandList commandList, CallbackInfo ci) {
        if (level.levelRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(level.levelRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.reset();
            }
        }
        // MC 1.21.1: Use getMinBuildHeight() instead of getMinY()
        this.bottomSectionY = ((net.minecraft.world.level.Level)this.world).getMinBuildHeight()>>4;
    }

    // Publish Embeddium's exact section search distance to the LOD-occlusion circle (ChunkBoundRenderer),
    // so the LOD mask ends exactly where Embeddium stops drawing vanilla sections.
    //
    // The NeoForge port also shrank this by one chunk so the outermost vanilla ring was drawn as LOD
    // instead. On Forge 1.20.1 + Embeddium that ring was frequently missing from the LOD store (its chunks
    // never become "ready" and their light is not applied yet when they arrive), which left chunk-sized
    // holes at the vanilla/LOD seam - so vanilla keeps its full render distance here.
    @ModifyArg(
        method = "createTerrainRenderList",
        at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/occlusion/OcclusionCuller;findVisible(Lme/jellysquid/mods/sodium/client/render/chunk/occlusion/OcclusionCuller$Visitor;Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;FZI)V"),
        index = 2
    )
    private float voxy$publishVanillaCullDistance(float searchDistance) {
        ChunkBoundRenderer.VANILLA_CULL_DISTANCE = searchDistance;
        return searchDistance;
    }

    // Central-band LOD culling (the FPS win): before Sodium walks the visibility graph, compute the
    // band-edge planes from the CURRENT camera view-projection. We do this at render-list build time,
    // which Sodium re-runs every frame the camera rotates, so the planes stay current. Skipped during
    // Iris' shadow pass (shadow casters must stay full-FOV).
    @Inject(method = "createTerrainRenderList", at = @At("HEAD"))
    private void voxy$prepareBandCull(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfo ci) {
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

    // ---- LOD mask = sections Embeddium actually draws ----
    // The LOD-occlusion mask (ChunkBoundRenderer) hides LOD wherever vanilla terrain is drawn. It must
    // only contain sections that are both BUILT and VISITED AS VISIBLE in the latest render-list build:
    // other mods change which sections Embeddium draws (Better FPS Render Distance culls an ellipsoid via
    // OcclusionCuller.isWithinRenderDistance, culling mods drop occluded sections, the centre band drops
    // the wings), and masking a section that is not drawn leaves a see-through hole.
    @Unique private final LongOpenHashSet voxy$built = new LongOpenHashSet();
    @Unique private LongOpenHashSet voxy$drawn = new LongOpenHashSet();
    @Unique private LongOpenHashSet voxy$traversal = new LongOpenHashSet();
    @Unique private boolean voxy$recording;
    @Unique private ChunkBoundRenderer voxy$lastBounds;

    @Unique
    private ChunkBoundRenderer voxy$bounds() {
        if (this.world.levelRenderer == null) return null;
        var system = ((IGetVoxyRenderSystem)(this.world.levelRenderer)).getVoxyRenderSystem();
        return system == null ? null : system.chunkBoundRenderer;
    }

    // Wrap the render-list sink: records which sections are drawn (see above), and drops sections that
    // project entirely into the wings when the centre band is active (no draw call, no vertex work).
    // Traversal is untouched (we wrap the visitor, not the culler), so connectivity/occlusion is unchanged.
    @ModifyArg(
        method = "createTerrainRenderList",
        at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/occlusion/OcclusionCuller;findVisible(Lme/jellysquid/mods/sodium/client/render/chunk/occlusion/OcclusionCuller$Visitor;Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;FZI)V"),
        index = 0
    )
    private OcclusionCuller.Visitor voxy$bandCullVisitor(OcclusionCuller.Visitor original) {
        // Shader shadow passes walk the graph from the light's point of view; they must not touch the mask.
        this.voxy$recording = !IrisUtil.irisShadowActive();
        if (this.voxy$recording) {
            this.voxy$traversal.clear();
        }
        final boolean band = CenterLodRegion.isBandCullActive();
        if (!band && !this.voxy$recording) {
            return original;
        }
        final var traversal = this.voxy$traversal;
        final boolean recording = this.voxy$recording;
        // Embeddium's VisibleChunkCollector also queues rebuilds from visit(), so a wing section is still
        // passed through - only flagged invisible - which keeps it out of the render list but lets it build.
        return (section, visible) -> {
            if (visible && band && CenterLodRegion.isSectionInWings(section.getChunkX() << 4, section.getChunkY() << 4, section.getChunkZ() << 4)) {
                visible = false;//Drop wing section from the render list
            }
            if (visible && recording) {
                traversal.add(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
            }
            original.visit(section, visible);
        };
    }

    @Inject(method = "createTerrainRenderList", at = @At("TAIL"))
    private void voxy$commitDrawnSections(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfo ci) {
        if (!this.voxy$recording) {
            return;
        }
        this.voxy$recording = false;
        var bounds = this.voxy$bounds();
        var prev = this.voxy$drawn;
        var now = this.voxy$traversal;
        if (bounds != null && bounds != this.voxy$lastBounds) {
            //A new (or reset) LOD renderer: rebuild its mask from scratch
            this.voxy$lastBounds = bounds;
            bounds.reset();
            var it = now.iterator();
            while (it.hasNext()) {
                long pos = it.nextLong();
                if (this.voxy$built.contains(pos)) bounds.addSection(pos);
            }
        } else if (bounds != null) {
            var it = prev.iterator();
            while (it.hasNext()) {
                long pos = it.nextLong();
                if (!now.contains(pos) && this.voxy$built.contains(pos)) {
                    bounds.removeSectionImmediate(pos);//No longer drawn by Embeddium -> let the LOD show
                }
            }
            it = now.iterator();
            while (it.hasNext()) {
                long pos = it.nextLong();
                if (!prev.contains(pos) && this.voxy$built.contains(pos)) {
                    bounds.addSection(pos);
                }
            }
        }
        this.voxy$drawn = now;
        this.voxy$traversal = prev;
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void injectIngest(int x, int z, CallbackInfo ci) {
        //TODO: Am not quite sure if this is right
        if (VoxyConfig.CONFIG.ingestEnabled && !BOBBY_INSTALLED) {
            var cccm = (ICheekyClientChunkCache)this.world.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.voxy$cheekyGetChunk(x, z);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }


    // When a section is disposed (chunk unloads / leaves render distance), explicitly
    // clear it from the LOD-occlusion bound. The build-state redirect (voxy$updateOnUpload) misses
    // this in 0.8 because RenderSection.setInfo() returns false during disposal, which made the
    // section keep masking Voxy's LOD - so the area went invisible for a moment until something
    // else cleared it. Keying matches voxy$updateOnUpload so add/remove line up.
    @Inject(method = "onSectionRemoved", at = @At("HEAD"))
    private void voxy$clearBoundOnSectionRemoved(int x, int y, int z, CallbackInfo ci) {
        if (this.world.levelRenderer == null) {
            return;
        }
        var system = ((IGetVoxyRenderSystem)(this.world.levelRenderer)).getVoxyRenderSystem();
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
        long pos = SectionPos.asLong(x, y, z);
        this.voxy$built.remove(pos);
        this.voxy$drawn.remove(pos);
        system.chunkBoundRenderer.removeSectionImmediate(pos);
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (this.world.levelRenderer != null && VoxyConfig.CONFIG.ingestEnabled) {
            var cccm = this.world.getChunkSource();
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
        if (this.world.worldRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(this.world.worldRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.removeSection(ChunkPos.toLong(x, z));
            }
        }
    }*/

    @Unique private long cachedChunkPos = -1;
    @Unique private int cachedChunkStatus;
    @Unique private int bottomSectionY;

    // Embeddium 0.3.x: RenderSection.setInfo returns void
    @Redirect(method = "updateSectionInfo", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;setInfo(Lme/jellysquid/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)V"))
    private void voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
        boolean wasBuilt = instance.getFlags()!=0;
        int flags = instance.getFlags();
        instance.setInfo(info);
        if (wasBuilt == (instance.getFlags()!=0)) {//Only want to do stuff on change
            return;
        }

        flags |= instance.getFlags();
        if (flags == 0)//Only process things with stuff
            return;

        {//Track built sections even while no Voxy renderer exists, so a renderer created later gets a correct mask
            long bpos = SectionPos.asLong(instance.getChunkX(), instance.getChunkY(), instance.getChunkZ());
            if (wasBuilt) this.voxy$built.remove(bpos); else this.voxy$built.add(bpos);
        }

        VoxyRenderSystem system = ((IGetVoxyRenderSystem)(this.world.levelRenderer)).getVoxyRenderSystem();
        if (system == null) {
            return;
        }
        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();

        if (wasBuilt && VoxyConfig.CONFIG.ingestEnabled) {
            var tracker = ((AccessorChunkTracker)ChunkTrackerHolder.get(this.world)).getChunkStatus();
            //in theory the cache value could be wrong but is so soso unlikely and at worst means we either duplicate ingest a chunk
            // which... could be bad ;-; or we dont ingest atall which is ok!
            long key = ChunkPos.asLong(x, z);
            if (key != this.cachedChunkPos) {
                this.cachedChunkPos = key;
                this.cachedChunkStatus = tracker.getOrDefault(key, 0);
            }
            if (this.cachedChunkStatus == 3) {//If this chunk still has surrounding chunks
                var section = this.world.getChunk(x,z).getSection(y-this.bottomSectionY);
                var lp = this.world.getLightEngine();

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
            system.chunkBoundRenderer.removeSection(pos);
        } else {//Add
            if (this.voxy$drawn.contains(pos)) {//Otherwise added once Embeddium actually draws it
                system.chunkBoundRenderer.addSection(pos);
            }
        }
    }
}
