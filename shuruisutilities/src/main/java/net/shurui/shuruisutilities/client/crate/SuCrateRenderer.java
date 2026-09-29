package net.shurui.shuruisutilities.client.crate;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;

import net.shurui.shuruisutilities.crate.block.SuCrateBlock;
import net.shurui.shuruisutilities.crate.block.SuCrateBlockEntity;

import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * Draws the SU crates.
 *
 * <p>The sixteen step turn is applied here rather than left to GeckoLib's four way block facing, and GeckoLib's
 * own rotation is disabled so the crate is not turned twice. Nothing is scaled: these models are furniture sized
 * and some are deliberately larger than their block.
 */
public class SuCrateRenderer extends GeoBlockRenderer<SuCrateBlockEntity>
{
    public SuCrateRenderer()
    {
        super(new SuCrateGeoModel());
    }

    @Override
    public void preRender(PoseStack poseStack, SuCrateBlockEntity crate,
                          software.bernie.geckolib.cache.object.BakedGeoModel model, MultiBufferSource buffer,
                          com.mojang.blaze3d.vertex.VertexConsumer vertexConsumer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, float red, float green,
                          float blue, float alpha)
    {
        if (!isReRender)
        {
            poseStack.translate(0.5D, 0.0D, 0.5D);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(
                    SuCrateBlock.rotationDegrees(crate.getBlockState())));
            poseStack.translate(-0.5D, 0.0D, -0.5D);
        }
        super.preRender(poseStack, crate, model, buffer, vertexConsumer, isReRender, partialTick, packedLight,
                packedOverlay, red, green, blue, alpha);
    }

    @Override
    protected void rotateBlock(Direction facing, PoseStack poseStack)
    {
    }

    @Override
    public RenderType getRenderType(SuCrateBlockEntity crate, net.minecraft.resources.ResourceLocation texture,
                                    MultiBufferSource buffer, float partialTick)
    {
        // cutout: the crate art uses transparent pixels around its trim, chains and the hitbox marker.
        return RenderType.entityCutoutNoCull(texture);
    }
}
