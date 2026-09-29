package net.shurui.shuruisutilities.racing.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.shuruisutilities.client.dball.DragonBallSets;
import net.shurui.shuruisutilities.client.dball.DragonBallShell;
import net.shurui.shuruisutilities.racing.client.compat.DmzKiOrbRender;
import net.shurui.shuruisutilities.racing.entity.RaceKiOrbEntity;
import net.shurui.shuruisutilities.racing.entity.RaceOrbKind;

/**
 * Draws a {@link RaceKiOrbEntity} (R8). The three ki projectiles (Ki Blast green, Hellzone red, Spirit Bomb blue)
 * borrow DragonMineZ's real ki VISUAL through {@link DmzKiOrbRender} (a compat delegate, guarded, with our own
 * translucent-sphere fallback if it is unavailable); a Ki Mine is a small pulsing yellow orb; a Fake Dragon Ball is
 * an item-box look with the tell (an off-orange shell and NO bob), so a racer can just tell it apart from a real box.
 *
 * <p>PRIVATE: draws nothing unless the server reported the racing feature installed ({@code ClientGate.feature},
 * never the licence {@code key()}). It carries no DMZ technique or damage logic (owner rule), only the visuals.
 */
public class RaceKiOrbRenderer extends EntityRenderer<RaceKiOrbEntity>
{
    private static final ResourceLocation TEXTURE = new ResourceLocation("dmz_ragnarok", "textures/entity/z_orb.png");

    // Ki colours: main / border / outline. Green Ki Blast and red Hellzone are coloured as the owner asked.
    private static final int GREEN_MAIN = 0x3CFF3C, GREEN_BORDER = 0x00A000, GREEN_OUTLINE = 0xCCFFCC;
    private static final int RED_MAIN = 0xFF2A2A, RED_BORDER = 0xA00000, RED_OUTLINE = 0xFFC0C0;
    private static final int SPIRIT_MAIN = 0x9FE7FF, SPIRIT_BORDER = 0x2F8BFF, SPIRIT_OUTLINE = 0xFFFFFF;
    private static final int MINE_RGB = 0xFFD23F;
    private static final int FAKE_SHELL = 0xFF5A3C;

    // Fake Dragon Ball uses the Namek ball look (the real item box) recoloured to the tell shade, at the box scale.
    private static final DragonBallSets.Look NAMEK = DragonBallSets.lookFor("namek");
    private static final float FAKE_RADIUS = NAMEK != null ? NAMEK.radius() : 5.75F / 16.0F;
    private static final float FAKE_CENTER_Y = NAMEK != null ? NAMEK.centerY() : 5.625F / 16.0F;
    private static final float FAKE_SCALE = 1.5F;

    public RaceKiOrbRenderer(EntityRendererProvider.Context context)
    {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(RaceKiOrbEntity orb, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight)
    {
        if (!ClientGate.feature("racing"))
            return;
        RaceOrbKind kind = orb.getKind();
        float size = orb.getSize();
        float age = orb.tickCount + partialTick;

        switch (kind)
        {
            case GREEN -> ki(orb, GREEN_MAIN, GREEN_BORDER, GREEN_OUTLINE, size, partialTick, pose, buffer, packedLight);
            case RED -> ki(orb, RED_MAIN, RED_BORDER, RED_OUTLINE, size, partialTick, pose, buffer, packedLight);
            case SPIRIT -> ki(orb, SPIRIT_MAIN, SPIRIT_BORDER, SPIRIT_OUTLINE, size, partialTick, pose, buffer,
                    packedLight);
            case MINE -> mine(pose, buffer, age, size);
            case FAKE -> fake(orb, pose, buffer, packedLight, age);
        }
    }

    // A ki projectile: DMZ's real ki visual through the guarded delegate, or our translucent sphere as a fallback.
    private void ki(RaceKiOrbEntity orb, int main, int border, int outline, float size, float partialTick,
                    PoseStack pose, MultiBufferSource buffer, int packedLight)
    {
        if (DmzKiOrbRender.render(orb, main, border, outline, size, orb.getYRot(), orb.getXRot(), partialTick, pose,
                buffer, packedLight))
            return;
        // Fallback: a tinted translucent glow sphere (the placed-ball shell primitive), sized to the orb.
        pose.pushPose();
        DragonBallShell.renderOrb(pose, buffer, Math.max(0.35F, size), main, 0.85F);
        pose.popPose();
    }

    // A Ki Mine: a small pulsing yellow orb sitting on the road.
    private void mine(PoseStack pose, MultiBufferSource buffer, float age, float size)
    {
        float pulse = 1.0F + 0.12F * (float) Math.sin(age * 0.25F);
        pose.pushPose();
        DragonBallShell.renderOrb(pose, buffer, Math.max(0.4F, size) * pulse, MINE_RGB, 0.9F);
        pose.popPose();
    }

    // A Fake Dragon Ball: the item-box ball look with the tell (off-orange shell, and NO bob), spinning like a box.
    private void fake(RaceKiOrbEntity orb, PoseStack pose, MultiBufferSource buffer, int packedLight, float age)
    {
        int stars = Math.floorMod(orb.getId(), 7) + 1;
        float spin = (float) ((age * 6.0) % 360.0);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(spin));
        pose.scale(FAKE_SCALE, FAKE_SCALE, FAKE_SCALE);
        pose.translate(-0.5D, 0.0D, -0.5D);
        DragonBallShell.render(pose, buffer, packedLight, age, FAKE_RADIUS, FAKE_CENTER_Y, FAKE_SHELL,
                DragonBallShell.PIP_RED, stars);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(RaceKiOrbEntity entity)
    {
        return TEXTURE;
    }
}
