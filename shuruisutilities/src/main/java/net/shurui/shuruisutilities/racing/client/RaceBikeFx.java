package net.shurui.shuruisutilities.racing.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.shuruisutilities.client.dball.DragonBallShell;
import net.shurui.shuruisutilities.hoverbike.HoverbikeEntity;
import net.shurui.shuruisutilities.racing.physics.RaceFx;

/**
 * The client-only FX layer drawn over a racing hoverbike (R7 self powerups): the god-of-destruction and Kaioken
 * auras, and the Flying Nimbus cloud under the bike. It reads only the bike's synced {@code DATA_RACE_FX} bits (for a
 * bot the server sets them from the physics; for a human rider the rider's own client sets them from its KartState),
 * so it never invents state, and it draws NOTHING unless the {@code racing} feature is synced.
 *
 * <p>DragonMineZ is a mandatory dependency, so its aura / kinton textures always resolve. The aura is a tinted
 * translucent glow sphere (the same shell primitive a placed dragon ball uses) plus a crown of emissive flame quads
 * cut from DMZ's god-aura / ki-aura sheets; the nimbus is a pair of spinning discs cut from DMZ's kinton texture. The
 * afterimage ghost trail is drawn by {@code HoverbikeRenderer} itself (it owns the baked bike model).
 */
public final class RaceBikeFx
{
    private RaceBikeFx() {}

    /**
     * The FX bits to draw for a bike from the viewer's perspective. A human racer's own client owns the full FX
     * (drift, boost, plus the auras) on {@code DATA_RACE_FX}, which never leaves that client; a remote human's bike
     * carries only the SERVER-authored aura bits on {@code DATA_RACE_AURA}. So: the local rider (and a bot, whose full
     * FX is server-synced) reads {@code DATA_RACE_FX}; a remote human's bike reads {@code DATA_RACE_AURA}. This is why
     * others see a racer's aura / nimbus / afterimage without the server ever fighting the client-authored physics FX.
     */
    public static int effectiveFx(HoverbikeEntity bike)
    {
        if (!bike.isRaceBike())
            return 0;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.entity.Entity controller = bike.getControllingPassenger();
        boolean humanRidden = controller instanceof net.minecraft.world.entity.player.Player;
        boolean localOwn = mc.player != null && controller == mc.player;
        if (humanRidden && !localOwn)
            return bike.getRaceAura();
        return bike.getRaceFx();
    }

    private static final String DMZ = "dragonminez";
    // The DARK Flying Nimbus (Bullet Bill): DMZ's black kinton texture, so the drawn-disc fallback matches the dark
    // BlackNimbus model the delegate renders.
    private static final ResourceLocation KINTON = new ResourceLocation(DMZ, "textures/entity/black_kinton.png");
    /** Our own kiwave shockwave ring (R11), for the Kiai burst under the user's bike. */
    private static final ResourceLocation KIWAVE =
            new ResourceLocation("dmz_ragnarok", "textures/entity/race/kiwave.png");
    /** Kiai ring fully-expanded radius (blocks), matching PowerupService.KIAI_RADIUS. */
    private static final float KIAI_MAX_RADIUS = 5.0F;

    private static final int DESTROYER_RGB = 0x9B30FF;
    private static final int FULL_BRIGHT = 0x00F000F0;

    /** Draw the aura / nimbus for a race bike. {@code pose} is at the entity origin (feet), the bike's own frame. */
    public static void render(HoverbikeEntity bike, PoseStack pose, MultiBufferSource buffers, int packedLight,
                              float partialTick)
    {
        if (!ClientGate.feature("racing") || !bike.isRaceBike())
            return;

        // Kiai shockwave ring (R11): an expanding kiwave ring on the ground under the user's OWN bike, driven by the
        // client timer (packet 112 KIAI). Drawn independently of the aura FX bits (Kiai leaves no lasting aura).
        Minecraft rmc = Minecraft.getInstance();
        if (rmc.player != null && bike.getControllingPassenger() == rmc.player)
        {
            float k = net.shurui.shuruisutilities.racing.client.RaceClientState.kiaiRingProgress();
            if (k >= 0.0F)
                drawKiaiRing(pose, buffers, k);

            // Hellzone triple: while the local racer holds a multi-charge Hellzone, its remaining shots orbit the bike
            // as red ki shields (r 1.6), fired one per use. Client visual from the held-slot state (packet 111).
            if (net.shurui.shuruisutilities.racing.client.RaceClientState.heldItem()
                    == net.shurui.shuruisutilities.racing.physics.PowerupKind.HELLZONE)
            {
                int shields = net.shurui.shuruisutilities.racing.client.RaceClientState.heldCharges();
                if (shields > 1)
                    drawHellzoneShields(bike, pose, buffers, shields);
            }
        }

        int fx = effectiveFx(bike);
        if (fx == 0)
            return;
        float age = bike.tickCount + partialTick;

        if (RaceFx.has(fx, RaceFx.NIMBUS))
            drawNimbus(bike, pose, buffers, age, partialTick);

        // Destroyer Aura (Star): a single purple glow orb over the bike, no flame crown and no particles. The purple
        // silhouette on the rider is a coloured GLOWING outline set server-side by DestroyerAura, not drawn here.
        // Kaioken (x3 / x20) draws NO bike aura and no orb: the BOOST speed streak plus the red / gold player outline
        // (also set server-side, tinted by the speed tier) carry it, so nothing extra is drawn on the bike here.
        if (RaceFx.has(fx, RaceFx.DESTROYER_AURA))
            drawDestroyerOrb(pose, buffers, age);
    }

    // Destroyer Aura orb: a single pulsing purple glow sphere over the bike (the dragon-ball shell primitive), with no
    // flame crown. The purple silhouette on the rider is the GLOWING outline the server-side effect sets.
    private static void drawDestroyerOrb(PoseStack pose, MultiBufferSource buffers, float age)
    {
        pose.pushPose();
        pose.translate(0.0, 0.75, 0.0);
        float pulse = 1.0F + 0.06F * (float) Math.sin(age * 0.35F);
        DragonBallShell.renderOrb(pose, buffers, 1.15F * pulse, DESTROYER_RGB, 0.34F);
        pose.popPose();
    }

    // The Flying Nimbus under the bike: DMZ's real kinton GeckoLib model, with a drawn-disc fallback if the delegate
    // is unavailable (guarded, DMZ internals). The bike's world position is passed so the delegate's dummy samples
    // the right light.
    private static void drawNimbus(HoverbikeEntity bike, PoseStack pose, MultiBufferSource buffers, float age,
                                   float partialTick)
    {
        if (net.shurui.shuruisutilities.racing.client.compat.DmzKintonRender.render(
                bike.getX(), bike.getY(), bike.getZ(), pose, buffers, partialTick, bike.getYRot()))
            return;
        drawNimbusFallback(pose, buffers, age);
    }

    /** Hellzone orbit radius (blocks), matching the plan's r 1.6. */
    private static final float HELLZONE_ORBIT = 1.6F;
    private static final int HELLZONE_RGB = 0xFF2A2A;

    // The Hellzone triple: N red ki-shield orbs circling the bike at HELLZONE_ORBIT, one per remaining charge. Reuses
    // the dragon-ball shell orb primitive (a small tinted glow sphere), emissive so it reads at any light.
    private static void drawHellzoneShields(HoverbikeEntity bike, PoseStack pose, MultiBufferSource buffers, int count)
    {
        float age = bike.tickCount + 0.0F;
        float spin = age * 4.0F;
        for (int i = 0; i < count; i++)
        {
            double yaw = Math.toRadians(spin + 360.0 * i / count);
            float ox = (float) (Math.cos(yaw) * HELLZONE_ORBIT);
            float oz = (float) (Math.sin(yaw) * HELLZONE_ORBIT);
            pose.pushPose();
            pose.translate(ox, 0.85, oz);
            DragonBallShell.renderOrb(pose, buffers, 0.5F, HELLZONE_RGB, 0.8F);
            pose.popPose();
        }
    }

    // The Kiai shockwave: a flat kiwave ring lying on the road under the bike, growing from small to KIAI_MAX_RADIUS as
    // progress goes 0 -> 1 and fading as it grows. Emissive so it reads day or night. Drawn in the bike's own frame at
    // its feet, so it stays centred on the user.
    private static void drawKiaiRing(PoseStack pose, MultiBufferSource buffers, float progress)
    {
        float radius = 0.6F + (KIAI_MAX_RADIUS - 0.6F) * progress;
        float alpha = (1.0F - progress) * 0.9F;
        if (alpha <= 0.02F)
            return;
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(KIWAVE));
        pose.pushPose();
        pose.translate(0.0, 0.06, 0.0);
        disc(pose, vc, radius, 1.0F, 1.0F, 1.0F, alpha);
        pose.popPose();
    }

    // A pair of spinning kinton discs just under the bike (fallback when the real model delegate is unavailable).
    private static void drawNimbusFallback(PoseStack pose, MultiBufferSource buffers, float age)
    {
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucentEmissive(KINTON));
        pose.pushPose();
        pose.translate(0.0, 0.02, 0.0);
        pose.mulPose(Axis.YP.rotationDegrees(age * 4.0F));
        disc(pose, vc, 1.4F, 1.0F, 1.0F, 1.0F, 0.95F);
        pose.mulPose(Axis.YP.rotationDegrees(37.0F));
        pose.translate(0.0, 0.18, 0.0);
        disc(pose, vc, 1.1F, 1.0F, 1.0F, 1.0F, 0.9F);
        pose.popPose();
    }

    // A flat horizontal disc-ish quad of half-size s at the current origin (the nimbus cloud), full sheet UV.
    private static void disc(PoseStack pose, VertexConsumer vc, float s, float r, float g, float b, float a)
    {
        PoseStack.Pose p = pose.last();
        vertexH(p, vc, -s, -s, 0, 0, r, g, b, a);
        vertexH(p, vc, -s, s, 0, 1, r, g, b, a);
        vertexH(p, vc, s, s, 1, 1, r, g, b, a);
        vertexH(p, vc, s, -s, 1, 0, r, g, b, a);
    }

    private static void vertexH(PoseStack.Pose p, VertexConsumer vc, float x, float z, float u, float v,
                                float r, float g, float b, float a)
    {
        vc.vertex(p.pose(), x, 0.0F, z).color(r, g, b, a).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(FULL_BRIGHT)
                .normal(p.normal(), 0.0F, 1.0F, 0.0F).endVertex();
    }
}
