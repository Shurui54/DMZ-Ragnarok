package net.shurui.shuruisutilities.ragnarok.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import net.shurui.shuruisutilities.client.saiyan.SaiyanFallbackLayer;
import net.shurui.shuruisutilities.client.saiyan.SaiyanFallbackRender;
import net.shurui.shuruisutilities.ragnarok.RgNpcFighterEntity;

/**
 * GeckoLib renderer for {@link RgNpcFighterEntity}, over {@link RgNpcFighterModel}.
 *
 * <p>Registering THIS renderer is exactly what keeps ragnarok art on a DragonMineZ saga entity: because the
 * fighter is a separate entity type with its own renderer, DragonMineZ's {@code DBSagasRenderer} never picks it
 * up, so it never resolves a saga geo from the type's registry path and never overwrites the chosen character.
 *
 * <p>When the rgnpc model IS installed it draws exactly as before, plain, with no armour or aura layers (those models
 * are authored complete, at their own size, with the armour bones removed on purpose). The one layer added, {@link
 * SaiyanFallbackLayer}, is inert on that common path; it paints a generated DragonMineZ saiyan ONLY when the model is
 * absent and {@link RgNpcFighterModel} has borrowed a DMZ race geo (see {@link RgNpcFighterModel#isFallback}).
 */
public class RgNpcFighterRenderer extends GeoEntityRenderer<RgNpcFighterEntity>
{
    public RgNpcFighterRenderer(EntityRendererProvider.Context context)
    {
        super(context, new RgNpcFighterModel());
        this.shadowRadius = 0.5F;
        addRenderLayer(new SaiyanFallbackLayer<>(this, RgNpcFighterModel::isFallback));
    }

    @Override
    public void preRender(PoseStack poseStack, RgNpcFighterEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
                          int packedLight, int packedOverlay, float red, float green, float blue, float alpha)
    {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight,
                packedOverlay, red, green, blue, alpha);
        // Apply the shared saiyan visibility baseline only when drawing the borrowed race geo (own model absent). A real
        // ragnarok geo has none of those bones. Guarded on !isReRender because the saiyan layers hide bones per re-render
        // pass; see SaiyanFallbackRender.applyVisibilityBaseline.
        if (!isReRender && animatable != null && RgNpcFighterModel.isFallback(animatable))
        {
            SaiyanFallbackRender.applyVisibilityBaseline(model, animatable);
        }
    }
}
