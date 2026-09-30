package net.shurui.dev.sdu.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.shurui.dev.sdu.entity.PilafMechEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * The Pilaf Mech's renderer. Scales the whole rig by {@link PilafMechEntity#SCALE} (the one place the fight's size
 * is defined, kept in step with the scaled bounding box so it is clickable and hittable where it is drawn). The
 * combined pilaf mecha model already reads as a boss at scale 1.0, so this is a simple GeckoLib renderer.
 */
public class PilafMechRenderer extends GeoEntityRenderer<PilafMechEntity> {

    public PilafMechRenderer(EntityRendererProvider.Context context) {
        super(context, new PilafMechModel());
        this.shadowRadius = 1.2f * PilafMechEntity.SCALE;
    }

    @Override
    public void preRender(PoseStack poseStack, PilafMechEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, float red, float green,
                          float blue, float alpha) {
        float s = PilafMechEntity.SCALE;
        poseStack.scale(s, s, s);
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, red, green, blue, alpha);
    }
}
