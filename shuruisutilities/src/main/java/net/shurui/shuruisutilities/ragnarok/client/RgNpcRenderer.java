package net.shurui.shuruisutilities.ragnarok.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.shurui.shuruisutilities.client.saiyan.SaiyanFallbackLayer;
import net.shurui.shuruisutilities.client.saiyan.SaiyanFallbackRender;
import net.shurui.shuruisutilities.ragnarok.RgNpcEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Standard GeckoLib entity renderer for {@link RgNpcEntity} over {@link RgNpcModel}, applying the per-entity
 * uniform render scale from the synced SCALE field.
 *
 * <p>Carries the {@link SaiyanFallbackLayer}, which paints a generated DragonMineZ saiyan ONLY when this NPC's own rgnpc
 * model is not installed and the model has therefore borrowed a DMZ race geo (see {@link RgNpcModel#isFallback}). On the
 * common installed path the layer is inert, so a present model renders exactly as before.
 */
public class RgNpcRenderer extends GeoEntityRenderer<RgNpcEntity> {

    public RgNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new RgNpcModel());
        this.shadowRadius = 0.5f;
        addRenderLayer(new SaiyanFallbackLayer<>(this, RgNpcModel::isFallback));
    }

    @Override
    public void preRender(PoseStack poseStack, RgNpcEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, float red, float green,
                          float blue, float alpha) {
        float scale = animatable.getScaleValue();
        if (scale != 1.0f && scale > 0.0f) {
            poseStack.scale(scale, scale, scale);
        }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, red, green, blue, alpha);
        // Only when the entity is drawing the borrowed race geo (its own model is absent) does the shared saiyan
        // visibility baseline apply: a real ragnarok geo has none of those bones, and running it there would be pointless.
        // Guarded on !isReRender for the reason spelled out on SaiyanFallbackRender.applyVisibilityBaseline: the saiyan
        // layers hide bones per re-render pass, and re-establishing the baseline on those passes would clobber the hides.
        if (!isReRender && animatable != null && RgNpcModel.isFallback(animatable)) {
            SaiyanFallbackRender.applyVisibilityBaseline(model, animatable);
        }
    }
}
