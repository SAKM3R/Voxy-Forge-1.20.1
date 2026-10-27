package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.mixin.minecraft.AccessorTextureAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore {
    public static final int MODEL_SIZE = 64;
    public static final int COLOUR_ENTRIES = 1<<22;
    final GlBuffer modelBuffer;
    final GlBuffer modelColourBuffer;
    final GlTexture textures;
    public final int blockSampler = glGenSamplers();

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        // Biome colour LUT: one entry per (biome dependent model, biome). 65536 entries overflowed in big modpacks
        // (hundreds of modded biomes x hundreds of tinted grass / leaves / vines), so the tail of the LUT was
        // written past the end of the buffer
        this.modelColourBuffer = new GlBuffer(4L * COLOUR_ENTRIES).fill(-1).name("ModelColour");
        this.textures = new GlTexture().store(GL_RGBA8, Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE), ModelFactory.MODEL_TEXTURE_SIZE*3*256,ModelFactory.MODEL_TEXTURE_SIZE*2*256).name("ModelTextures");
        // A new texture is not cleared: on some drivers it comes back holding the atlas of the renderer that was just
        // freed (another block's texture in every slot). Start it transparent, so a slot nothing was baked into shows nothing.
        for (int lvl = 0; lvl < ModelFactory.LAYERS; lvl++) {
            org.lwjgl.opengl.GL44.glClearTexImage(this.textures.id, lvl, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        }


        // MC 1.21.1: TextureAtlas.mipLevel is now private, use accessor mixin
        TextureAtlas blockAtlas = (TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS);
        int mipLvl = ((AccessorTextureAtlas) blockAtlas).voxy$getMipLevel();

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
    }


    public GlTexture getTextures() {
        return this.textures;
    }

    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        this.textures.free();
        glDeleteSamplers(this.blockSampler);
    }


    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        glBindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }
}
