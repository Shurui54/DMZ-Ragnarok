package net.shurui.shuruisutilities.client.corrupted;

import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.corrupted.ShadowShenronEntity;

import software.bernie.geckolib.model.GeoModel;

// geomodel for the shadow shenron prop. fixed SU asset paths (no per-entity config): the jar ships the
// geo, texture and animation under these exact locations. the animation file's single looping clip is "idle",
// requested by the entity's controller.
public final class ShadowShenronModel extends GeoModel<ShadowShenronEntity>
{
    private static final ResourceLocation GEO =
            new ResourceLocation(ShuruisUtilities.MODID, "geo/entity/shadow_shenron.geo.json");
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/shadow_shenron.png");
    private static final ResourceLocation ANIM =
            new ResourceLocation(ShuruisUtilities.MODID, "animations/entity/shadow_shenron.animation.json");

    @Override
    public ResourceLocation getModelResource(ShadowShenronEntity animatable)
    {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(ShadowShenronEntity animatable)
    {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ShadowShenronEntity animatable)
    {
        return ANIM;
    }
}
