package net.shurui.shuruisutilities.client.space;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.core.SUConfig;

/**
 * Real gravitational lensing of the sky around a black hole. Runs at {@link RenderLevelStageEvent.Stage#AFTER_SKY}, the
 * one stage where the main framebuffer holds the sky and SU's procedural starfield but NOT terrain, entities or clouds
 * (see the render-stage note in {@link SpaceBodyRenderer}). We copy the main colour into a persistent intermediate, rebind
 * main for write, then draw a single fullscreen quad through {@code shuruisutilities:lensing}, which samples the
 * INTERMEDIATE and displaces each sample toward the hole. Sampling and writing the same target in one draw is illegal, so
 * the copy-first split is mandatory. The black hole geometry (sphere, spherical rim, discs, arcs) is drawn AFTER this, so
 * it sits on top of the warped sky.
 *
 * <h3>Blit, not a shader copy</h3>
 * The main->intermediate copy is a raw {@code glBlitFramebuffer}, not a passthrough shader draw, on purpose: a
 * position_tex copy would be transformed by RenderSystem's live world view/projection matrices (this is a world pass, not
 * GUI), which would mangle a fullscreen NDC quad. The lensing vertex shader itself is matrix-free (NDC in, NDC out), so
 * only ONE custom draw happens and it needs no matrix juggling.
 *
 * <h3>Nearest hole only</h3>
 * The shader handles ONE hole. Rather than stack a fullscreen grab-and-lens per hole, we lens the SINGLE nearest black
 * hole to the camera each frame. Two overlapping holes are astronomically unlikely at these spacings, and the nearest is
 * the one whose warp reads.
 *
 * <h3>Mandatory degrade path</h3>
 * The whole pass is gated behind {@link #lensingSafe}, computed once: false if a shader mod (oculus / iris / optifine) is
 * loaded, because those replace the pipeline and grabbing their G-buffer is garbage. Iris shaderpack-active is probed
 * behind {@code Class.forName} catching {@link Throwable} (presence is not API compatibility). Any exception in the grab
 * or draw permanently disables the pass (first-failure self-disable, the same policy the sdu FormPreview outline uses).
 * A {@link SUConfig#lensingEnabled} toggle (default on) can turn it off outright. When off, the black hole still renders
 * everything else, a strict upgrade on the old look.
 */
public final class BlackHoleLensing
{
    private BlackHoleLensing()
    {
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    // bend strength, in the shader's 0.15..0.4 tuning band. Higher warps the sky harder near the horizon.
    private static final float STRENGTH = 0.28F;
    // below this projected screen radius (in screen-height fractions) the hole is a speck; skip so we never lens a pixel.
    private static final float MIN_SCREEN_RADIUS = 0.004F;

    // the persistent intermediate the main colour is blitted into each frame, resized with the window. Never sampled and
    // written in the same draw (see the class note).
    private static TextureTarget grabTarget;

    // the one-shot degrade state. lensingSafe starts optimistic and is pinned false forever on the first shader-mod
    // detection or the first thrown exception. safetyComputed guards the one-time mod probe; statusLogged and
    // runtimeDisableLogged keep the diagnostics to exactly one line each so a silent degrade is always visible in the log.
    private static boolean lensingSafe = true;
    private static boolean safetyComputed = false;
    private static boolean statusLogged = false;
    private static boolean runtimeDisableLogged = false;

    /**
     * Compute the degrade state once and log the enabled/disabled status one time. Cheap and idempotent, so the caller
     * can invoke it every frame it is in space; the actual work runs only on the first call.
     */
    public static void ensureStatus()
    {
        if (safetyComputed)
        {
            return;
        }
        safetyComputed = true;

        if (!SUConfig.lensingEnabled)
        {
            // config-off is not a permanent-unsafe state (a reload could flip it), but the mod probe below is, so only
            // report the config here and leave lensingSafe alone.
            logStatus(false, "SU config BlackHoleLensing=false");
            return;
        }

        String blocker = shaderModBlocker();
        if (blocker != null)
        {
            lensingSafe = false;
            logStatus(false, "shader mod present (" + blocker + "); its pipeline replaces the framebuffer, sampling it "
                    + "would be garbage");
            return;
        }
        logStatus(true, null);
    }

    // which shader mod, if any, forces the flat (non-lensing) path. Presence check first, then an Iris shaderpack-active
    // probe behind Class.forName catching Throwable, because a modid being loaded is not the same as its API being there.
    private static String shaderModBlocker()
    {
        try
        {
            if (ModList.get().isLoaded("oculus"))
            {
                return "oculus";
            }
            if (ModList.get().isLoaded("optifine"))
            {
                return "optifine";
            }
            if (ModList.get().isLoaded("iris"))
            {
                return "iris";
            }
        }
        catch (Throwable ignored)
        {
            // ModList not ready or absent: treat as no blocker and let the try/catch around the draw catch real trouble.
        }
        try
        {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            Object inUse = api.getMethod("isShaderPackInUse").invoke(instance);
            if (inUse instanceof Boolean b && b)
            {
                return "iris-shaderpack-active";
            }
        }
        catch (Throwable ignored)
        {
            // no Iris API on the classpath, or a version whose signature moved: not a blocker via this probe.
        }
        return null;
    }

    /** True if the pass may run this frame (config on, no shader mod, shader compiled, not self-disabled). */
    public static boolean isActive()
    {
        return SUConfig.lensingEnabled && lensingSafe && SuShaders.lensing() != null;
    }

    /**
     * Lens the sky around one black hole. {@code holeDrawPos} is the hole's camera-relative drawn position (world axes,
     * camera at origin, already through the shell depth map) and {@code drawRadius} its drawn radius, exactly as
     * {@link SpaceBodyRenderer} draws the body, so the warp lands on the silhouette. No-op when the pass is inactive or
     * the hole is behind the camera.
     */
    public static void render(RenderLevelStageEvent event, double drawX, double drawY, double drawZ, float drawRadius)
    {
        if (!isActive() || drawRadius <= 0.0F)
        {
            return;
        }
        ShaderInstance shader = SuShaders.lensing();
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        if (w <= 0 || h <= 0)
        {
            return;
        }

        // project the hole centre and a vertical edge point (centre + camera-up * drawRadius) through proj*view, so the
        // uv-space centre and radius match exactly where the sphere is drawn. Vertical offset keeps the radius in clean
        // screen-height fractions, the same units the shader's aspect-corrected distance uses.
        Camera camera = event.getCamera();
        Vector3f up = camera.getUpVector();
        Matrix4f view = event.getPoseStack().last().pose();
        Matrix4f pv = new Matrix4f(event.getProjectionMatrix()).mul(view);

        Vector4f centre = new Vector4f((float) drawX, (float) drawY, (float) drawZ, 1.0F).mul(pv);
        if (centre.w <= 0.0F)
        {
            // behind the camera: nothing to warp.
            return;
        }
        Vector4f edge = new Vector4f((float) drawX + up.x() * drawRadius,
                (float) drawY + up.y() * drawRadius,
                (float) drawZ + up.z() * drawRadius, 1.0F).mul(pv);
        if (edge.w <= 0.0F)
        {
            return;
        }

        float cx = (centre.x() / centre.w()) * 0.5F + 0.5F;
        float cy = (centre.y() / centre.w()) * 0.5F + 0.5F;
        float ex = (edge.x() / edge.w()) * 0.5F + 0.5F;
        float ey = (edge.y() / edge.w()) * 0.5F + 0.5F;
        float screenRadius = (float) Math.sqrt((ex - cx) * (ex - cx) + (ey - cy) * (ey - cy));
        if (screenRadius < MIN_SCREEN_RADIUS)
        {
            return;
        }
        float aspect = (float) w / (float) h;

        try
        {
            // 1) grab: blit the main colour into the intermediate. A raw framebuffer blit avoids the matrix transform a
            //    shader copy would suffer in a world pass (see the class note).
            if (grabTarget == null)
            {
                grabTarget = new TextureTarget(w, h, false, Minecraft.ON_OSX);
                grabTarget.setFilterMode(GL11.GL_NEAREST);
            }
            else if (grabTarget.width != w || grabTarget.height != h)
            {
                grabTarget.resize(w, h, Minecraft.ON_OSX);
            }
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, grabTarget.frameBufferId);
            GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            // 2) rebind main for write and lens: one fullscreen NDC quad sampling the intermediate.
            main.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.setShader(SuShaders::lensing);
            RenderSystem.setShaderTexture(0, grabTarget.getColorTextureId());

            setUniform(shader, "HoleScreenPos", cx, cy);
            setUniform(shader, "HoleRadius", screenRadius);
            setUniform(shader, "Aspect", aspect);
            setUniform(shader, "Strength", STRENGTH);

            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            // NDC quad, uv up = NDC up = framebuffer bottom-left origin, so no V flip: uv maps straight to screen.
            bb.vertex(-1.0F, -1.0F, 0.0F).uv(0.0F, 0.0F).endVertex();
            bb.vertex(-1.0F, 1.0F, 0.0F).uv(0.0F, 1.0F).endVertex();
            bb.vertex(1.0F, 1.0F, 0.0F).uv(1.0F, 1.0F).endVertex();
            bb.vertex(1.0F, -1.0F, 0.0F).uv(1.0F, 0.0F).endVertex();
            BufferUploader.drawWithShader(bb.end());

            restoreState();
            logStatus(true, null);
        }
        catch (Throwable t)
        {
            lensingSafe = false;
            restoreState();
            if (!runtimeDisableLogged)
            {
                runtimeDisableLogged = true;
                LOGGER.warn("[SU] black hole lensing PERMANENTLY DISABLED after a runtime failure: {}. The void still "
                        + "renders its sphere, spherical rim, discs and wrap arcs.", t.toString());
            }
        }
    }

    private static void setUniform(ShaderInstance shader, String name, float a)
    {
        var u = shader.getUniform(name);
        if (u != null)
        {
            u.set(a);
        }
    }

    private static void setUniform(ShaderInstance shader, String name, float a, float b)
    {
        var u = shader.getUniform(name);
        if (u != null)
        {
            u.set(a, b);
        }
    }

    // put GL state back to what the body pass expects (depth on and writing, cull on, blend off, white modulator), and
    // rebind main for write so a mid-pass failure cannot leave the intermediate bound.
    private static void restoreState()
    {
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void logStatus(boolean enabled, String reason)
    {
        if (statusLogged)
        {
            return;
        }
        statusLogged = true;
        if (enabled)
        {
            LOGGER.info("[SU] black hole lensing ENABLED: fullscreen screen-space warp of the starfield at AFTER_SKY "
                    + "(blit main->intermediate, lens nearest hole only, strength={}), no mixin, no shader mod detected.",
                    STRENGTH);
        }
        else
        {
            LOGGER.info("[SU] black hole lensing DISABLED ({}). The void still renders its sphere, spherical rim, discs "
                    + "and wrap arcs.", reason);
        }
    }
}
