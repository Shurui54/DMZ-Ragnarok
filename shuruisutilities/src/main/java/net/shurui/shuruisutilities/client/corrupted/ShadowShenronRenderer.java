package net.shurui.shuruisutilities.client.corrupted;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

import net.shurui.shuruisutilities.corrupted.ShadowShenronEntity;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

// standard geckolib renderer for the shadow shenron prop over ShadowShenronModel.
public final class ShadowShenronRenderer extends GeoEntityRenderer<ShadowShenronEntity>
{
    // VISUAL TUNING VALUES (adjust after a launch test): the geo is authored roughly 16 blocks tall with
    // cube-origin Y up to about +260 model units and visible_bounds_offset [0, 8.25, 0], so at scale 1.0 it
    // renders huge and floating high above the entity position. these two constants set the intended cinematic
    // size and height. RENDER_SCALE 3.5 puts the ~16-block model near ~56 blocks tall (a towering, sky-filling
    // dragon meant to loom over the altar). RENDER_TRANSLATE_Y -10.0 nudges the whole thing back down toward the
    // altar so it does not hover absurdly high after the scale. because the translate is applied BEFORE the scale
    // it runs in unscaled world units, so the downward correction the model needs grows linearly with the scale:
    // at scale 0.35 it took -1.0, so at 10x (scale 3.5) it takes -1.0 * (3.5 / 0.35) = -10.0. both are starting
    // guesses, expect to re-tune them in game.
    private static final float RENDER_SCALE = 3.5F;
    private static final float RENDER_TRANSLATE_Y = -10.0F;

    public ShadowShenronRenderer(EntityRendererProvider.Context context)
    {
        super(context, new ShadowShenronModel());
        // grow the ground shadow with the model (0.35 -> 3.5 is 10x, so 1.5 -> 15.0). well under the vanilla
        // MAX_SHADOW_RADIUS clamp of 32.0 in EntityRenderDispatcher, so 15.0 is passed through untouched.
        this.shadowRadius = 15.0F;
    }

    @Override
    public void preRender(PoseStack poseStack, ShadowShenronEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, float red, float green,
                          float blue, float alpha)
    {
        poseStack.translate(0.0F, RENDER_TRANSLATE_Y, 0.0F);
        poseStack.scale(RENDER_SCALE, RENDER_SCALE, RENDER_SCALE);
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, red, green, blue, alpha);
    }
}
