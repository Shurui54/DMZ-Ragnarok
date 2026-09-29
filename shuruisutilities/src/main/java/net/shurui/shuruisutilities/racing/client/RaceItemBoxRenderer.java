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
import net.shurui.shuruisutilities.racing.entity.RaceItemBoxEntity;

/**
 * Draws a {@link RaceItemBoxEntity} as a Namek dragon ball at 1.5x that SPINS and BOBS, the Mario-Kart item box in
 * DBZ dress (owner rule). It reuses the exact glass-ball drawing the placed dragon balls use
 * ({@link DragonBallShell#render}) with the NAMEK {@link DragonBallSets.Look} (radius 5.75/16, colour 0xFF9E00, red
 * pips), so a box reads as the real Namek ball and never a placeholder. The star count is {@code entityId % 7 + 1}
 * so a row of boxes shows the whole one-to-seven star spread.
 *
 * <p>PRIVATE: it returns without drawing unless the connected server reported the racing feature installed
 * ({@code ClientGate.feature("racing")}, NOT {@code key()}, which is the licence check), so a stray or summoned box
 * is invisible on a keyless client until it discards itself server-side.
 *
 * <p>Motion: a {@code (tick+pt)*6} deg/tick Y spin about the ball's own axis and a {@code 0.15*sin((tick+pt)*0.1)}
 * block vertical bob. While the synced HIDDEN flag holds (the 40-tick respawn) it draws nothing; when the box pops
 * back it eases a scale overshoot from {@link RaceItemBoxEntity#clientPopProgress} so the reappear reads as a pop.
 */
public class RaceItemBoxRenderer extends EntityRenderer<RaceItemBoxEntity>
{
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("dmz_ragnarok", "textures/entity/z_orb.png");

    /** Base scale: 1.5x the Namek ball (~1.08 blocks), per the owner's spec. */
    private static final float SCALE = 1.5F;
    /** Reappear pop length, ticks. */
    private static final int POP_TICKS = 6;

    // The Namek look, resolved once; fall back to the literal plan values if the set table ever changes.
    private static final DragonBallSets.Look NAMEK = DragonBallSets.lookFor("namek");
    private static final float RADIUS = NAMEK != null ? NAMEK.radius() : 5.75F / 16.0F;
    private static final float CENTER_Y = NAMEK != null ? NAMEK.centerY() : 5.625F / 16.0F;
    private static final int COLOUR = NAMEK != null ? NAMEK.baseRgb() : 0xFF9E00;
    private static final float[] PIP = NAMEK != null ? NAMEK.pip() : DragonBallShell.PIP_RED;

    public RaceItemBoxRenderer(EntityRendererProvider.Context context)
    {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(RaceItemBoxEntity box, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight)
    {
        // Client-side gate: a private feature the server did not install draws nothing (never key(), the licence).
        if (!ClientGate.feature("racing"))
            return;
        // Hidden during its respawn window: draw nothing until it pops back.
        if (box.isHidden())
            return;

        float age = box.tickCount + partialTick;
        int stars = Math.floorMod(box.getId(), 7) + 1;

        float spin = (float) ((age * 6.0) % 360.0);
        double bob = 0.15 * Math.sin(age * 0.1);
        float pop = popScale(box.clientPopProgress(partialTick, POP_TICKS));
        float scale = SCALE * pop;

        pose.pushPose();
        // bob first (so the whole ball rises/falls), then spin+scale about the ball's own vertical axis at the
        // entity origin, then the block-local pre-translate so DragonBallShell's own (+0.5, +0.5) centres the ball
        // back on the entity origin (its horizontal +0.5 cancels the -0.5 here; its centreY raises it off the floor).
        pose.translate(0.0D, bob, 0.0D);
        pose.mulPose(Axis.YP.rotationDegrees(spin));
        pose.scale(scale, scale, scale);
        pose.translate(-0.5D, 0.0D, -0.5D);

        DragonBallShell.render(pose, buffer, packedLight, age, RADIUS, CENTER_Y, COLOUR, PIP, stars);

        pose.popPose();
    }

    // easeOutBack: from ~0 at progress 0, overshoots past 1 near the end, settles at 1, so the pop reads springy.
    private static float popScale(float p)
    {
        if (p >= 1.0F)
            return 1.0F;
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        float x = p - 1.0F;
        return 1.0F + c3 * x * x * x + c1 * x * x;
    }

    @Override
    public ResourceLocation getTextureLocation(RaceItemBoxEntity entity)
    {
        return TEXTURE;
    }
}
