package net.shurui.dev.sdu.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.shurui.dev.sdu.entity.DukeSnipperjackEntity;
import net.shurui.dev.sdu.entity.PumpkinPuppetEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * GeckoLib renderer for the pumpkin puppet minion. Scaled by the same {@link DukeSnipperjackEntity#SCALE} as the
 * Duke so the entourage stays in proportion with the boss (it reads that one constant, kept in step with the
 * puppet's scaled bounding box).
 */
public class PumpkinPuppetRenderer extends GeoEntityRenderer<PumpkinPuppetEntity> {

    public PumpkinPuppetRenderer(EntityRendererProvider.Context context) {
        super(context, new PumpkinPuppetModel());
        this.shadowRadius = 0.4f * DukeSnipperjackEntity.SCALE;
    }

    @Override
    public void preRender(PoseStack poseStack, PumpkinPuppetEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, float red, float green,
                          float blue, float alpha) {
        float s = DukeSnipperjackEntity.SCALE;
        poseStack.scale(s, s, s);
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, red, green, blue, alpha);
    }
}
