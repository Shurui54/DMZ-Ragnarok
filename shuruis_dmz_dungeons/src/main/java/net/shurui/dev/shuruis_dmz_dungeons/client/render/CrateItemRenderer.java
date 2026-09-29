package net.shurui.dev.shuruis_dmz_dungeons.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import net.shurui.dev.shuruis_dmz_dungeons.item.CrateBlockItem;
import net.shurui.shuruisutilities.client.crate.CrateItemFit;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * Draws a dungeon crate in a slot, a hand or an item frame as the crate the stack will place.
 *
 * <p>Shares {@link CrateItemFit} with the SU crates: both are block models drawn as items, both furniture sized,
 * both wanting centring on what they actually draw rather than a per-model display transform tuned by hand.
 */
public class CrateItemRenderer extends GeoItemRenderer<CrateBlockItem>
{
    public CrateItemRenderer()
    {
        super(new CrateItemModel());
        // Flat item lighting would light every face identically and flatten a three dimensional crate into a sticker.
        useAlternateGuiLighting();
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext transformType, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay)
    {
        // The one point where the stack, and so which of the nineteen looks this is, is known for certain. Everything
        // downstream reads it back off the model.
        ((CrateItemModel) getGeoModel()).setStack(stack);
        super.renderByItem(stack, transformType, poseStack, bufferSource, packedLight, packedOverlay);
    }

    @Override
    public long getInstanceId(CrateBlockItem crate)
    {
        // Per LOOK, not per stack. Every variant is the same item, so GeckoLib's default (one id for every stack
        // without a GeckoLib NBT id) would run all nineteen through one animation state; the controller caches the
        // animation it resolved, so the next look would be told to move bones its rig lacks, and GeckoLib crashes
        // on a missing bone rather than skipping.
        return ((CrateItemModel) getGeoModel()).currentName().hashCode();
    }

    @Override
    public void preRender(PoseStack poseStack, CrateBlockItem crate, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay, float red, float green,
                          float blue, float alpha)
    {
        super.preRender(poseStack, crate, model, bufferSource, buffer, isReRender, partialTick, packedLight,
                packedOverlay, red, green, blue, alpha);

        if (!isReRender)
        {
            // Undo GeckoLib's half block nudge (right for a model authored around the origin, wrong for a block
            // model standing on its floor) and centre on the crate's own bounds instead. Render layers come back
            // through here with isReRender set and must not be moved twice.
            poseStack.translate(-0.5F, -0.51F, -0.5F);
            CrateItemFit.apply(poseStack, getGeoModel().getModelResource(crate, this), model);
        }
    }

    @Override
    public RenderType getRenderType(CrateBlockItem crate, ResourceLocation texture, MultiBufferSource buffer,
                                    float partialTick)
    {
        return RenderType.entityCutoutNoCull(texture);
    }
}
