package me.cortex.voxy.client.core.rendering;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/**
 * Computes (and applies) the screen-space "central band" used to decide where vanilla chunks keep
 * rendering and where Voxy LOD takes over.
 *
 * <p>The band is a screen-centred, full-window-height column whose width is {@code monitorWidth *
 * (lodCenterWidthPct / 100)} pixels. Everything inside the column renders as vanilla; the left/right
 * wings outside it render as LOD. Because the width is measured against the MONITOR (not the window),
 * at 100% the band is always at least as wide as the game window, so nothing changes - that is the
 * default. This is applied as a GL scissor rectangle around (a) Sodium's vanilla terrain draw and
 * (b) Voxy's LOD-occlusion mask, so the two stay pixel-perfectly aligned and track camera rotation
 * and window resizing for free.
 */
public final class CenterLodRegion {
    private CenterLodRegion() {}

    /**
     * @return the scissor box {@code {x, y, width, height}} (in framebuffer pixels, GL bottom-left
     * origin) of the central vanilla column, or {@code null} when culling is inactive (band wide
     * enough to cover the whole window, e.g. the default 100%).
     */
    public static int[] computeScissor() {
        int pct = VoxyConfig.CONFIG.lodCenterWidthPct;
        if (pct >= 100) {
            return null;//Fast path: 100% never culls anything
        }

        var window = Minecraft.getInstance().getWindow();
        int fbW = window.getWidth();
        int fbH = window.getHeight();
        int screenW = window.getScreenWidth();//Window width in screen coords (matches the monitor video-mode units)
        if (fbW <= 0 || fbH <= 0 || screenW <= 0) {
            return null;
        }

        double monitorW = screenW;//Fallback to the window width if no monitor is detected (e.g. headless/odd setups)
        var monitor = window.findBestMonitor();
        if (monitor != null && monitor.getCurrentMode() != null) {
            monitorW = monitor.getCurrentMode().getWidth();
        }

        //Band width as a fraction of the window width. Computed in screen-coord space (monitor video mode
        //and getScreenWidth() share units) so it is HiDPI-correct, then scaled to framebuffer pixels.
        double frac = (monitorW * (pct / 100.0)) / screenW;
        if (frac >= 1.0) {
            return null;//Band covers (or exceeds) the window -> no wings -> nothing to cull
        }

        int bandW = (int) Math.round(frac * fbW);
        if (bandW >= fbW) {
            return null;
        }
        if (bandW < 1) {
            bandW = 1;
        }
        int x = (fbW - bandW) / 2;//Centre the column on the window
        return new int[]{x, 0, bandW, fbH};
    }

    /**
     * Enables a scissor test clipped to {@code box} (via {@link GlStateManager} so MC's state cache
     * stays in sync). The world-render phase leaves the scissor test disabled, so {@link #restoreScissor}
     * just turns it back off. Avoids glGet* queries, which would stall the GPU pipeline.
     *
     * @return {@code true} if a scissor was applied (so it should be restored), {@code false} when
     * {@code box} is {@code null} (culling inactive).
     */
    public static boolean applyScissor(int[] box) {
        if (box == null) {
            return false;
        }
        GlStateManager._enableScissorTest();
        GlStateManager._scissorBox(box[0], box[1], box[2], box[3]);
        return true;
    }

    /** Disables the scissor test if {@link #applyScissor} enabled one. No-op when {@code applied} is false. */
    public static void restoreScissor(boolean applied) {
        if (applied) {
            GlStateManager._disableScissorTest();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Render-list cull (the actual FPS win): drop vanilla sections that fall entirely in the screen
    // "wings" outside the central band, so Sodium never issues their draw calls or vertex work. The
    // scissor above still does the exact pixel boundary on the sections we KEEP, so this cull only has
    // to be conservative: we cull a section only when its whole AABB projects beyond the band + a small
    // margin. Sodium rebuilds its render list every frame the camera rotates, so the planes below are
    // recomputed often enough that the margin never needs to absorb more than sub-frame drift.
    // ---------------------------------------------------------------------------------------------

    // Extra half-width (in NDC) kept as vanilla beyond the visible band, as a safety margin against
    // matrix precision and the section being a 16-block box. Keeps the band hole-free.
    private static final float CULL_MARGIN_NDC = 0.10f;

    private static volatile boolean cullActive = false;
    private static double camX, camY, camZ;
    // Band edge planes in camera-relative space. plane.(rel,1) >= 0 means "inside" that edge.
    private static float lA, lB, lC, lD;   // left edge  (ndc.x >= -k)
    private static float rA, rB, rC, rD;   // right edge (ndc.x <=  k)

    /** Resets cull state. Call at the start of each render-list build before {@link #setupBandCull}. */
    public static void beginBandCull() {
        cullActive = false;
    }

    /**
     * Computes the left/right band-edge planes from the camera view-projection so that
     * {@link #isSectionInWings} can reject sections projecting entirely into the wings.
     *
     * @param vp           camera view-projection (maps camera-relative world coords -> clip space)
     * @param bandHalfNdc  half-width of the visible vanilla band in NDC (0..1)
     */
    public static void setupBandCull(double cx, double cy, double cz, Matrix4f vp, double bandHalfNdc) {
        float k = (float) bandHalfNdc + CULL_MARGIN_NDC;
        if (k >= 1.0f) {
            cullActive = false;//Band + margin already covers the whole screen -> nothing to cull
            return;
        }
        // clip.x = row0 . v ; clip.w = row3 . v   (JOML mXY = column X, row Y)
        float x0 = vp.m00(), x1 = vp.m10(), x2 = vp.m20(), x3 = vp.m30();//row0 (clip.x)
        float w0 = vp.m03(), w1 = vp.m13(), w2 = vp.m23(), w3 = vp.m33();//row3 (clip.w)
        // Right edge: inside when k*clip.w - clip.x >= 0  ->  plane = k*row3 - row0
        rA = k * w0 - x0; rB = k * w1 - x1; rC = k * w2 - x2; rD = k * w3 - x3;
        // Left edge: inside when k*clip.w + clip.x >= 0  ->  plane = k*row3 + row0
        lA = k * w0 + x0; lB = k * w1 + x1; lC = k * w2 + x2; lD = k * w3 + x3;
        camX = cx; camY = cy; camZ = cz;
        cullActive = true;
    }

    public static boolean isBandCullActive() {
        return cullActive;
    }

    /**
     * @return true if the 16-block section at the given block-space origin projects entirely into a
     * wing (beyond the band + margin) and may be skipped. Robust for sections behind the camera
     * (homogeneous plane test, no perspective divide).
     */
    public static boolean isSectionInWings(int blockX, int blockY, int blockZ) {
        if (!cullActive) {
            return false;
        }
        double x0 = blockX - camX, y0 = blockY - camY, z0 = blockZ - camZ;
        double x1 = x0 + 16, y1 = y0 + 16, z1 = z0 + 16;
        // Fully in the right wing if even the AABB's most-inside corner is still outside the right edge.
        double rMax = rA * (rA > 0 ? x1 : x0) + rB * (rB > 0 ? y1 : y0) + rC * (rC > 0 ? z1 : z0) + rD;
        if (rMax < 0) {
            return true;
        }
        double lMax = lA * (lA > 0 ? x1 : x0) + lB * (lB > 0 ? y1 : y0) + lC * (lC > 0 ? z1 : z0) + lD;
        return lMax < 0;
    }
}
