package net.shurui.shuruisutilities.racing.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

import software.bernie.geckolib.renderer.GeoEntityRenderer;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.shuruisutilities.racing.entity.RaceSaibamanEntity;

/**
 * GeckoLib renderer for the race Saibaman (R9), over {@link RaceSaibamanModel} (DragonMineZ's reused saga-saibaman
 * art). PRIVATE: it draws nothing unless the connected server reported the racing feature installed
 * ({@code ClientGate.feature("racing")}, never the licence {@code key()}), so a stray or summoned saibaman is
 * invisible on a keyless client until it discards itself server-side.
 */
public class RaceSaibamanRenderer extends GeoEntityRenderer<RaceSaibamanEntity>
{
    public RaceSaibamanRenderer(EntityRendererProvider.Context context)
    {
        super(context, new RaceSaibamanModel());
        this.shadowRadius = 0.4F;
    }

    @Override
    public void render(RaceSaibamanEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight)
    {
        if (!ClientGate.feature("racing"))
            return;
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }
}
