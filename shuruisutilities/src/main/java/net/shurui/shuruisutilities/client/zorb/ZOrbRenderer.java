package net.shurui.shuruisutilities.client.zorb;

import java.awt.Color;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.shuruisutilities.client.dball.DragonBallShell;
import net.shurui.shuruisutilities.zorb.ZOrbEntity;
import net.shurui.shuruisutilities.zorb.ZOrbKind;

/**
 * Draws a {@link ZOrbEntity} as a small translucent energy sphere the size of a Namek dragon ball, built from the
 * SAME glass-ball drawing the placed dragon balls use ({@link DragonBallShell#renderOrb}). Inside sits a soft round
 * emissive glow and a billboarded icon that names the reward: a red aura glyph for TP, a gold zeni glyph for zeni,
 * and the rolled item itself for the item tail (over a soft pearlescent hue-shifting glow). The FINAL orb of a chain
 * is 25% bigger than the others.
 *
 * <p>PRIVATE: it returns without drawing unless the connected server reported the Z orb feature installed
 * ({@code ClientGate.feature("zorbs")}). A keyless client draws nothing.
 */
public class ZOrbRenderer extends EntityRenderer<ZOrbEntity>
{
    private static final ResourceLocation TEXTURE = new ResourceLocation("dmz_ragnarok", "textures/entity/z_orb.png");

    // The zeni glyph the HUD uses (ScouterStatHudOverlay TEX_RW_ZENI_Z), and DMZ's own aura icon for TP.
    private static final ResourceLocation TEX_ZENI = new ResourceLocation("dmz_ragnarok", "textures/gui/hud/rework/zeni_z.png");
    private static final ResourceLocation TEX_AURA = new ResourceLocation("dragonminez", "textures/gui/radial/aura.png");

    /** A normal orb matches a Namek dragon ball (~0.59 block); the last orb is 25% bigger. */
    private static final float RADIUS_NORMAL = 0.30F;
    private static final float LAST_SCALE = 1.25F;

    private static final float ALPHA_ACTIVE = 0.62F;
    private static final float ALPHA_INACTIVE = 0.35F;
    private static final int GREY = 0x9AA0A6;

    public ZOrbRenderer(EntityRendererProvider.Context context)
    {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(ZOrbEntity orb, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffer,
                       int packedLight)
    {
        if (!ClientGate.feature("zorbs"))
            return;

        ZOrbKind kind = orb.getKind();
        boolean item = kind == ZOrbKind.ITEM;
        boolean last = orb.isLast();
        float baseRadius = RADIUS_NORMAL * (last ? LAST_SCALE : 1.0F);

        float age = orb.tickCount + partialTick;
        double bob = Math.sin(age * 0.12 + orb.getIndex() * 0.6) * 0.08;

        boolean active = orb.isActiveOrb();
        UUID local = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getUUID() : null;
        boolean claimedByOther = orb.getClaimant().isPresent()
                && (local == null || !orb.getClaimant().get().equals(local));

        int rgb = claimedByOther ? GREY : (item ? pearlescent(age) : orb.getColour());
        float pulse = active && !claimedByOther ? 1.06F + 0.06F * (float) Math.sin(age * 0.16) : 1.0F;
        float radius = baseRadius * pulse;
        float alpha = active && !claimedByOther ? ALPHA_ACTIVE : ALPHA_INACTIVE;

        pose.pushPose();
        pose.translate(0.0D, 0.3D + bob, 0.0D);

        // 1) emissive core glow, then the reward icon / item, drawn BEFORE the shell and flushed so the glass blends
        //    in front and they read as sitting inside the ball.
        drawCore(pose, buffer, baseRadius, rgb, alpha);
        if (item)
        {
            ItemStack stack = orb.getStack();
            if (!stack.isEmpty())
                drawItem(pose, buffer, stack, age, orb, baseRadius);
        }
        else if (kind == ZOrbKind.ZENI)
        {
            drawIcon(pose, buffer, TEX_ZENI, baseRadius, age, alpha);
        }
        else // TP
        {
            drawIcon(pose, buffer, TEX_AURA, baseRadius, age, alpha);
        }

        if (buffer instanceof MultiBufferSource.BufferSource bs)
            bs.endBatch();

        // 2) the translucent shell + rim over the core / icon.
        DragonBallShell.renderOrb(pose, buffer, radius, rgb, alpha);

        pose.popPose();
    }

    /** A soft, low-saturation rainbow that shifts slowly (~8 s cycle), not garish. */
    private static int pearlescent(float age)
    {
        float hue = (age * 0.0075F) % 1.0F;
        if (hue < 0)
            hue += 1.0F;
        return Color.HSBtoRGB(hue, 0.35F, 1.0F) & 0xFFFFFF;
    }

    private static final int GLOW_SEGMENTS = 24;

    private void drawCore(PoseStack pose, MultiBufferSource buffer, float baseRadius, int rgb, float alpha)
    {
        int core = DragonBallShell.lighten(rgb);
        float r = ((core >> 16) & 0xFF) / 255.0F;
        float g = ((core >> 8) & 0xFF) / 255.0F;
        float b = (core & 0xFF) / 255.0F;
        float centerAlpha = Math.min(1.0F, alpha + 0.35F);
        float rad = baseRadius * 0.62F;

        pose.pushPose();
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(DragonBallShell.SHELL_SHEET));
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        for (int i = 0; i < GLOW_SEGMENTS; i++)
        {
            double a0 = 2.0 * Math.PI * i / GLOW_SEGMENTS;
            double a1 = 2.0 * Math.PI * (i + 1) / GLOW_SEGMENTS;
            float x0 = (float) Math.cos(a0) * rad;
            float y0 = (float) Math.sin(a0) * rad;
            float x1 = (float) Math.cos(a1) * rad;
            float y1 = (float) Math.sin(a1) * rad;
            glowVertex(vc, m, n, 0.0F, 0.0F, r, g, b, centerAlpha);
            glowVertex(vc, m, n, x0, y0, r, g, b, 0.0F);
            glowVertex(vc, m, n, x1, y1, r, g, b, 0.0F);
            glowVertex(vc, m, n, x1, y1, r, g, b, 0.0F);
        }
        pose.popPose();
    }

    private static void glowVertex(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y,
                                   float r, float g, float b, float alpha)
    {
        vc.vertex(m, x, y, 0.0F)
                .color(r, g, b, alpha)
                .uv(0.5F, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(n, 0.0F, 0.0F, 1.0F)
                .endVertex();
    }

    // A camera-facing textured quad for the reward glyph (zeni_z / aura), full-bright, with a gentle bob so it reads
    // as floating inside the ball. Drawn on the emissive translucent pass so it keeps its brightness through the glass.
    private void drawIcon(PoseStack pose, MultiBufferSource buffer, ResourceLocation tex, float baseRadius, float age,
                          float alpha)
    {
        float bob = (float) Math.sin(age * 0.09) * 0.02F;
        // A flat camera-facing quad has to sit INSIDE the shell sphere (radius == baseRadius at the inactive pulse),
        // so its half-diagonal (s * sqrt(2)) must stay under baseRadius. 0.55 keeps the whole glyph, corners and all,
        // comfortably within the ball at every orb size (the last orb scales baseRadius, so this stays proportional).
        float s = baseRadius * 0.55F;
        float a = Math.min(1.0F, alpha + 0.3F);

        pose.pushPose();
        pose.translate(0.0D, bob, 0.0D);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(tex));
        iconVertex(vc, m, n, -s, -s, 0.0F, 1.0F, a);
        iconVertex(vc, m, n, s, -s, 1.0F, 1.0F, a);
        iconVertex(vc, m, n, s, s, 1.0F, 0.0F, a);
        iconVertex(vc, m, n, -s, s, 0.0F, 0.0F, a);
        pose.popPose();
    }

    private static void iconVertex(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y, float u, float v, float a)
    {
        vc.vertex(m, x, y, 0.0F)
                .color(1.0F, 1.0F, 1.0F, a)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(n, 0.0F, 0.0F, 1.0F)
                .endVertex();
    }

    private void drawItem(PoseStack pose, MultiBufferSource buffer, ItemStack stack, float age, ZOrbEntity orb,
                          float baseRadius)
    {
        Minecraft mc = Minecraft.getInstance();
        float bob = (float) Math.sin(age * 0.09) * 0.03F;
        float tilt = (float) Math.sin(age * 0.06) * 7.0F;
        float scale = baseRadius / RADIUS_NORMAL * 0.5F; // keep the item proportional to the (bigger) last orb
        pose.pushPose();
        pose.translate(0.0D, bob, 0.0D);
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(tilt));
        pose.scale(scale, scale, scale);
        var model = mc.getItemRenderer().getModel(stack, orb.level(), null, orb.getId());
        mc.getItemRenderer().render(stack, ItemDisplayContext.FIXED, false, pose, buffer, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, model);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(ZOrbEntity entity)
    {
        return TEXTURE;
    }
}
