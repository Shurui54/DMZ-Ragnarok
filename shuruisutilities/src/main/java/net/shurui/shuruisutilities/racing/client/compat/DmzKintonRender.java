package net.shurui.shuruisutilities.racing.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;

import com.dragonminez.common.init.MainEntities;
import com.dragonminez.common.init.entities.BlackNimbusEntity;

/**
 * Renders DragonMineZ's DARK nimbus (Black Nimbus) GeckoLib model under a racing hoverbike, by delegating to DMZ's
 * own registered {@code BlackNimbusRenderer} through a client-only dummy {@link BlackNimbusEntity} that is NEVER
 * added to the world (so it carries no DMZ mob behaviour, only its geo / texture / animation). DragonMineZ is a
 * mandatory dependency, so its classes always resolve; even so every call is guarded and, on any failure, marks the
 * delegate dead for the session so {@code RaceBikeFx} falls back to its own drawn cloud discs.
 *
 * <p>One shared dummy is enough: the delegate renders synchronously (unlike the ki-attack queue), so re-posing it per
 * bike per frame is correct even with several nimbus riders on screen at once.
 */
public final class DmzKintonRender
{
    private DmzKintonRender() {}

    private static BlackNimbusEntity dummy;
    private static boolean dead;

    /**
     * Draw the kinton under the bike at the current pose origin (the bike's feet). Returns false (having drawn
     * nothing) if the delegate is unavailable, so the caller can draw its fallback.
     *
     * @param yaw the bike's yaw so the cloud faces with it.
     */
    public static boolean render(double x, double y, double z, PoseStack pose, MultiBufferSource buffers,
                                 float partialTick, float yaw)
    {
        if (dead)
            return false;
        try
        {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null)
                return false;
            BlackNimbusEntity e = dummy;
            if (e == null || e.level() != mc.level)
            {
                e = new BlackNimbusEntity(MainEntities.BLACK_NIMBUS.get(), mc.level);
                dummy = e;
            }
            // Position the dummy at the bike (so any internal light / culling sample reads the bike's daylight), and
            // draw it FULL-BRIGHT: a nimbus cloud is a lit object, and a mispositioned dummy would otherwise sample
            // darkness and render black.
            e.setPos(x, y, z);
            e.xOld = x;
            e.yOld = y;
            e.zOld = z;
            e.setYRot(yaw);
            e.yRotO = yaw;
            e.setYBodyRot(yaw);
            e.yBodyRotO = yaw;
            e.setYHeadRot(yaw);
            e.yHeadRotO = yaw;

            @SuppressWarnings("unchecked")
            EntityRenderer<BlackNimbusEntity> renderer =
                    (EntityRenderer<BlackNimbusEntity>) mc.getEntityRenderDispatcher().getRenderer(e);
            if (renderer == null)
                return false;

            pose.pushPose();
            // sit the cloud just under the bike's base; DMZ's kinton model is built around the entity origin.
            pose.translate(0.0, -0.15, 0.0);
            pose.scale(1.15F, 1.15F, 1.15F);
            renderer.render(e, yaw, partialTick, pose, buffers, LightTexture.FULL_BRIGHT);
            pose.popPose();
            return true;
        }
        catch (Throwable t)
        {
            dead = true;
            return false;
        }
    }
}
