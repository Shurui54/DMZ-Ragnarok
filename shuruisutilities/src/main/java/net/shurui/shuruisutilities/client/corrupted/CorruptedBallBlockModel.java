package net.shurui.shuruisutilities.client.corrupted;

import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.corrupted.CorruptedBallBlockEntity;

import software.bernie.geckolib.model.GeoModel;

/**
 * Client-only GeoModel for the placed swap balls. Points GeckoLib at the SU-copied dragon ball geo and picks the
 * darker recoloured texture by the block's star number (1..7). Mirrors DMZ's DragonBallBlockModel, but with the
 * SU namespace and a fixed geo (SU has a single copied geo, not DMZ's data-driven set definitions).
 *
 * <p>FOLLOW-UP: the geo path below must match the geometry file at
 * assets/shuruisutilities/geo/block/corrupted_dball.geo.json. GeckoLib resolves the model by this file path
 * (the internal "identifier" string inside DMZ's geo is "geometry.unknown", which GeckoLib ignores for a single
 * geometry), so the file name is what has to line up.
 */
public final class CorruptedBallBlockModel extends GeoModel<CorruptedBallBlockEntity>
{
    private static final ResourceLocation GEO =
            new ResourceLocation(ShuruisUtilities.MODID, "geo/block/corrupted_dball.geo.json");

    // DMZ's dball animation file, reused directly. DMZ is a mandatory dependency, so this resource always
    // resolves at runtime and SU does not need to ship its own animation asset.
    private static final ResourceLocation ANIM =
            new ResourceLocation("dragonminez", "animations/block/dball.animation.json");

    @Override
    public ResourceLocation getModelResource(CorruptedBallBlockEntity animatable)
    {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(CorruptedBallBlockEntity animatable)
    {
        int star = Math.max(1, Math.min(7, animatable.getStar()));
        return new ResourceLocation(ShuruisUtilities.MODID, "textures/block/custom/corrupted_dballblock" + star + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(CorruptedBallBlockEntity animatable)
    {
        return ANIM;
    }
}
