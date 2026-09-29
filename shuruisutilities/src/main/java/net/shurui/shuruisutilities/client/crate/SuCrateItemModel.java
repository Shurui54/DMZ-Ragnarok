package net.shurui.shuruisutilities.client.crate;

import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.crate.block.SuCrateBlockItem;

import software.bernie.geckolib.model.GeoModel;

/**
 * Points a crate ITEM at the same three files the placed crate uses. The block form asks its block entity, the item
 * form asks the item, and both end up at the crate's name in three folders.
 */
public final class SuCrateItemModel extends GeoModel<SuCrateBlockItem>
{
    @Override
    public ResourceLocation getModelResource(SuCrateBlockItem crate)
    {
        return new ResourceLocation(ShuruisUtilities.MODID, "geo/block/crate/" + crate.modelName() + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(SuCrateBlockItem crate)
    {
        return new ResourceLocation(ShuruisUtilities.MODID, "textures/" + crate.texturePath() + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(SuCrateBlockItem crate)
    {
        return new ResourceLocation(ShuruisUtilities.MODID,
                "animations/block/crate/" + crate.modelName() + ".animation.json");
    }
}
