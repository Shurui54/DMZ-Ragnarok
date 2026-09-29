package net.shurui.dev.sdu.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.shurui.dev.sdu.entity.ShenronDisplayEntity;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer for the {@link ShenronDisplayEntity} - a standard GeckoLib entity renderer over {@link ShenronModel}
 * with a per-entity uniform scale applied from the configured {@code entityScale}.
 */
public class ShenronRenderer extends GeoEntityRenderer<ShenronDisplayEntity> {

    public ShenronRenderer(EntityRendererProvider.Context context) {
        super(context, new ShenronModel());
        this.shadowRadius = 1.0f;
    }

    @Override
    public void preRender(PoseStack poseStack, ShenronDisplayEntity animatable, software.bernie.geckolib.cache.object.BakedGeoModel model,
                          net.minecraft.client.renderer.MultiBufferSource bufferSource, com.mojang.blaze3d.vertex.VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        float scale = animatable.getScaleValue();
        if (scale != 1.0f && scale > 0.0f) {
            poseStack.scale(scale, scale, scale);
        }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, red, green, blue, alpha);
    }
}
