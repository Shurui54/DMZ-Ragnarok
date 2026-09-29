package net.shurui.shuruisutilities.client.crate;

import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.crate.block.SuCrateBlockEntity;

import software.bernie.geckolib.model.GeoModel;

/**
 * Points a crate at its art. Geometry, texture and animations share the crate's name, so the three lookups are
 * the same string in three folders, and which name that is comes from the block itself.
 */
public final class SuCrateGeoModel extends GeoModel<SuCrateBlockEntity>
{
    @Override
    public ResourceLocation getModelResource(SuCrateBlockEntity crate)
    {
        return new ResourceLocation(ShuruisUtilities.MODID, "geo/block/crate/" + crate.modelName() + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(SuCrateBlockEntity crate)
    {
        return new ResourceLocation(ShuruisUtilities.MODID, "textures/" + crate.texturePath() + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(SuCrateBlockEntity crate)
    {
        return new ResourceLocation(ShuruisUtilities.MODID,
                "animations/block/crate/" + crate.modelName() + ".animation.json");
    }
}
