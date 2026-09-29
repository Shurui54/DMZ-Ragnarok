package net.shurui.shuruisutilities.client.crate;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.crate.block.SuCrateBlockItem;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * Draws an SU crate in a slot, a hand or an item frame as the crate model itself.
 *
 * <p>Everything specific to an item lives in {@link #preRender}: GeckoLib's own offset assumes a model authored
 * around the origin the way an item is, and a crate is authored the way a block is, standing on the floor of its
 * block and sometimes wider than it. {@link CrateItemFit} replaces that offset with one measured from the crate.
 */
public class SuCrateItemRenderer extends GeoItemRenderer<SuCrateBlockItem>
{
    public SuCrateItemRenderer()
    {
        super(new SuCrateItemModel());
        // Flat item lighting would light every face of a three dimensional crate identically, which reads as a
        // sticker. This is the lighting an entity gets in a GUI, so the crate keeps its shading.
        useAlternateGuiLighting();
    }

    @Override
    public void preRender(PoseStack poseStack, SuCrateBlockItem crate, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, float red, float green,
                          float blue, float alpha)
    {
        super.preRender(poseStack, crate, model, bufferSource, buffer, isReRender, partialTick, packedLight,
                packedOverlay, red, green, blue, alpha);

        if (!isReRender)
        {
            // Undo the half block nudge GeckoLib just applied, then centre and scale on the crate's own bounds.
            // Render layers come back through here with isReRender set and must NOT be moved a second time.
            poseStack.translate(-0.5F, -0.51F, -0.5F);
            CrateItemFit.apply(poseStack, getGeoModel().getModelResource(crate, this), model);
        }
    }

    @Override
    public RenderType getRenderType(SuCrateBlockItem crate, ResourceLocation texture, MultiBufferSource buffer,
                                    float partialTick)
    {
        // cutout, matching the block renderer: the crate art uses transparent pixels around its trim and chains.
        return RenderType.entityCutoutNoCull(texture);
    }
}
