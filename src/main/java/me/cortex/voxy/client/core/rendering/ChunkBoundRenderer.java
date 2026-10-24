package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;

//This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
public class ChunkBoundRenderer {
    private static final int INIT_MAX_CHUNK_COUNT = 1<<12;
    private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_CHUNK_COUNT*8);//Stored as ivec2
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(INIT_MAX_CHUNK_COUNT);
    private long[] idx2chunk = new long[INIT_MAX_CHUNK_COUNT];
    private final Shader rasterShader;

    private final LongOpenHashSet addQueue = new LongOpenHashSet();
    private final LongOpenHashSet remQueue = new LongOpenHashSet();
    // Tracks sections currently sitting in a delayed-removal slot, so removeSection is idempotent
    // (it can be driven from multiple Sodium hooks for the same section without double-removing).
    private final LongOpenHashSet pendingRemoval = new LongOpenHashSet();

    // Delayed removal to prevent pop-out when chunks unload
    // Each entry is processed after REMOVAL_DELAY_FRAMES frames
    // 12 frames @ 60fps = ~200ms delay for LOD system to prepare
    private static final int REMOVAL_DELAY_FRAMES = 12;

    // Set each frame by MixinRenderSectionManager.voxy$cullOutermostVanillaRing to the EXACT distance
    // (blocks) within which Sodium still renders vanilla (its search distance minus our 1-chunk cull).
    // The LOD-occlusion circle below uses this so it lands precisely on the vanilla cull edge. A fixed
    // renderDistance*16 - 16 under-shoots it (Sodium's search distance carries a buffer), leaving a
    // thin LOD-over-vanilla overlap (the faint water bug); over-shooting would leave 1-chunk holes.
    // -1 until Sodium's first render-list build; renderDistance falls back to the estimate until then.
    public static volatile float VANILLA_CULL_DISTANCE = -1.0f;
    private final LongArrayList[] delayedRemovalQueue = new LongArrayList[REMOVAL_DELAY_FRAMES];
    private int delayQueueIndex = 0;

    private final AbstractRenderPipeline pipeline;
    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.chunk2idx.defaultReturnValue(-1);
        this.pipeline = pipeline;

        // Initialize delayed removal queues
        for (int i = 0; i < REMOVAL_DELAY_FRAMES; i++) {
            this.delayedRemovalQueue[i] = new LongArrayList();
        }

        String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            vert = vert+"\n\n\n"+taa;
        }
        this.rasterShader = Shader.makeAuto()
                .addSource(ShaderType.VERTEX, vert)
                .defineIf("TAA", taa != null)
                .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
                .compile()
                .ubo(0, this.uniformBuffer)
                .ssbo(1, this.chunkPosBuffer);
    }

    public void addSection(long pos) {
        // First check if it's pending removal in any delay queue
        if (this.pendingRemoval.remove(pos)) {
            for (LongArrayList queue : this.delayedRemovalQueue) {
                if (queue.rem(pos)) {
                    break;
                }
            }
            return; // Was pending removal, now cancelled
        }
        if (!this.remQueue.remove(pos)) {
            this.addQueue.add(pos);
        }
    }

    public void removeSection(long pos) {
        if (this.addQueue.remove(pos)) {
            return; // Was a pending add that never became live; just cancel it
        }
        // Only sections actually being masked need removing. Sodium drives this from several places
        // (build-state change and section disposal), so ignore anything not currently tracked to
        // avoid spurious "not in map" churn, and dedupe via pendingRemoval.
        if (!this.chunk2idx.containsKey(pos)) {
            return;
        }
        if (this.pendingRemoval.add(pos)) {
            // Add to delayed removal queue instead of immediate removal
            // This gives LOD system time to prepare before chunk bounds disappear
            this.delayedRemovalQueue[this.delayQueueIndex].add(pos);
        }
    }

    // Removes a section from the occlusion bound on the next frame, skipping the delayed-removal
    // window. Use this when Sodium has actually disposed the section (chunk unloaded / left render
    // distance): the LOD is already prepared, so the ~200ms delay would only leave a see-through
    // gap. The delayed path is still used for transient build-state changes (rebuilds).
    public void removeSectionImmediate(long pos) {
        if (this.addQueue.remove(pos)) {
            return; // Was a pending add that never became live; just cancel it
        }
        if (!this.chunk2idx.containsKey(pos)) {
            return;
        }
        // Cancel any in-flight delayed removal so we don't process it twice.
        if (this.pendingRemoval.remove(pos)) {
            for (LongArrayList queue : this.delayedRemovalQueue) {
                if (queue.rem(pos)) {
                    break;
                }
            }
        }
        this.remQueue.add(pos);
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    public void render(Viewport<?> viewport) {
        // Process delayed removals - rotate to next slot and move oldest entries to remQueue
        int oldestSlot = (this.delayQueueIndex + 1) % REMOVAL_DELAY_FRAMES;
        LongArrayList oldestQueue = this.delayedRemovalQueue[oldestSlot];
        if (!oldestQueue.isEmpty()) {
            for (int i = 0; i < oldestQueue.size(); i++) {
                long p = oldestQueue.getLong(i);
                this.remQueue.add(p);
                this.pendingRemoval.remove(p);
            }
            oldestQueue.clear();
        }
        this.delayQueueIndex = oldestSlot;

        if (!this.remQueue.isEmpty()) {
            boolean wasEmpty = this.chunk2idx.isEmpty();
            this.remQueue.forEach(this::_remPos);//TODO: REPLACE WITH SCATTER COMPUTE
            this.remQueue.clear();
            if (this.chunk2idx.isEmpty()&&!wasEmpty) {//When going from stuff to nothing need to clear the depth buffer
                viewport.depthBoundingBuffer.clear(0);
            }
        }

        if (this.chunk2idx.isEmpty() && this.addQueue.isEmpty()) return;

        viewport.depthBoundingBuffer.clear(0);

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr; ptr += 4*4*4;

        // Land the LOD-occlusion circle EXACTLY on the vanilla cull edge produced by
        // MixinRenderSectionManager.voxy$cullOutermostVanillaRing, so the outermost ring is clean LOD:
        // no holes (circle too far out) and no LOD-over-vanilla overlap (circle too far in). We read
        // Sodium's actual post-cull search distance that the mixin shares with us, instead of guessing
        // renderDistance*16 - 16 (that under-shoots because Sodium's search distance carries a buffer,
        // which left the faint residual water bug).
        final float renderDistance = VANILLA_CULL_DISTANCE >= 0.0f
                ? VANILLA_CULL_DISTANCE
                : Math.max(Minecraft.getInstance().options.getEffectiveRenderDistance()*16 - 16.0f, 16.0f);//In blocks

        {//This is recomputed to be in chunk section space not worldsection
            int sx = (int)(viewport.cameraX);
            int sy = (int)(viewport.cameraY);
            int sz = (int)(viewport.cameraZ);
            new Vector3i(sx, sy, sz).getToAddress(ptr); ptr += 4*4;

            var negInnerSec = new Vector3f(
                    (float) (viewport.cameraX - sx),
                    (float) (viewport.cameraY - sy),
                    (float) (viewport.cameraZ - sz));


            negInnerSec.getToAddress(ptr); ptr += 4*3;
            viewport.MVP.translate(negInnerSec.negate(), new Matrix4f()).getToAddress(matPtr);
            MemoryUtil.memPutFloat(ptr, renderDistance); ptr += 4;

            // LOD boundary buffer - configurable overlap to prevent pop-in
            MemoryUtil.memPutInt(ptr, VoxyConfig.CONFIG.lodBoundaryBuffer); ptr += 4;
        }
        UploadStream.INSTANCE.commit();


        {
            //need to reverse the winding order since we want the back faces of the AABB, not the front

            glFrontFace(GL_CW);//Reverse winding order

            //"reverse depth buffer" it goes from 0->1 where 1 is far away
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_GREATER);
        }

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.bind();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        this.pipeline.bindUniforms();

        // Central-band LOD culling: clip the occlusion mask to the centre vanilla column so the LOD
        // wings are never masked (the buffer was already fully cleared to 0 above, so the wings stay 0
        // = "no vanilla here" = LOD draws). The matching vanilla-terrain scissor (MixinDefaultChunkRenderer)
        // uses the SAME rect, so vanilla and the mask are clipped on the exact same pixel edge.
        boolean bandScissor = CenterLodRegion.applyScissor(CenterLodRegion.computeScissor());

        //Batch the draws into groups of size 32
        int count = this.chunk2idx.size();
        if (count >= 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, count/32);
        }
        if (count%32 != 0) {
            glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6 * 2 * 3 * (count%32), GL_UNSIGNED_BYTE, 0, 1, (count/32)*32);
        }

        CenterLodRegion.restoreScissor(bandScissor);

        {
            glFrontFace(GL_CCW);//Restore winding order

            glDepthFunc(GL_LEQUAL);

            //TODO: check this is correct
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }


        if (!this.addQueue.isEmpty()) {
            this.addQueue.forEach(this::_addPos);//TODO: REPLACE WITH SCATTER COMPUTE
            this.addQueue.clear();
            UploadStream.INSTANCE.commit();
        }
    }

    private void _remPos(long pos) {
        int idx = this.chunk2idx.remove(pos);
        if (idx == -1) {
            Logger.warn("Chunk not in map: " + pos);
            return;
        }
        if (idx == this.chunk2idx.size()) {
            //Dont need to do anything as heap is already compact
            return;
        }
        if (this.idx2chunk[idx] != pos) {
            throw new IllegalStateException();
        }

        //Move last entry on heap to this index
        long ePos = this.idx2chunk[this.chunk2idx.size()];// since is already removed size is correct end idx
        if (this.chunk2idx.put(ePos, idx) == -1) {
            throw new IllegalStateException();
        }
        this.idx2chunk[idx] = ePos;

        //Put the end pos into the new idx
        this.put(idx, ePos);
    }

    private void _addPos(long pos) {
        if (this.chunk2idx.containsKey(pos)) {
            Logger.warn("Chunk already in map: " + pos);
            return;
        }
        this.ensureSize1();//Resize if needed

        int idx = this.chunk2idx.size();
        this.chunk2idx.put(pos, idx);
        this.idx2chunk[idx] = pos;

        this.put(idx, pos);
    }

    private void ensureSize1() {
        if (this.chunk2idx.size() < this.idx2chunk.length) return;
        //Commit any copies, ensures is synced to new buffer
        UploadStream.INSTANCE.commit();

        int size = (int) (this.idx2chunk.length*1.5);
        Logger.info("Resizing chunk position buffer to: " + size);
        //Need to resize
        var old = this.chunkPosBuffer;
        this.chunkPosBuffer = new GlBuffer(size * 8L);
        glCopyNamedBufferSubData(old.id, this.chunkPosBuffer.id, 0, 0, old.size());
        old.free();
        var old2 = this.idx2chunk;
        this.idx2chunk = new long[size];
        System.arraycopy(old2, 0, this.idx2chunk, 0, old2.length);
        //Replace the old buffer with the new one
        ((AutoBindingShader)this.rasterShader).ssbo(1, this.chunkPosBuffer);
    }

    private void put(int idx, long pos) {
        long ptr2 = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L*idx, 8);
        //Need to do it in 2 parts because ivec2 is 2 parts
        MemoryUtil.memPutInt(ptr2, (int)(pos&0xFFFFFFFFL)); ptr2 += 4;
        MemoryUtil.memPutInt(ptr2, (int)((pos>>>32)&0xFFFFFFFFL));
    }

    public void reset() {
        this.chunk2idx.clear();
        this.remQueue.clear();
        this.addQueue.clear();
        this.pendingRemoval.clear();
        for (LongArrayList queue : this.delayedRemovalQueue) {
            queue.clear();
        }
    }

    public void free() {
        this.rasterShader.free();
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
    }
}
