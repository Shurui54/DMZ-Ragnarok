package net.shurui.shuruisutilities.client.space;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.space.SuperBallEntity;
import net.shurui.shuruisutilities.space.SuperPlanetPositions;

/**
 * {@link GeoModel} for the collectible {@link SuperBallEntity}. It points straight at the SU-owned 4x ball geo that the
 * Super ball BLOCK already uses ({@code geo/block/dball_super4x.geo.json}); GeckoLib's geo format is identical for a
 * block and an entity, so the same file drives both. The texture is the per-star ball art ({@code dballblock_super1..7}),
 * and the animation is DragonMineZ's own ball idle ({@code dragonminez:animations/block/dball.animation.json}).
 *
 * <p>The geo is authored feet-at-origin: across every inflated cube the minimum y is exactly 0, so the model's base
 * sits on the entity's feet and rises about 2.81 blocks. No origin recentre is needed; the model bottom already lines
 * up with the bottom of the {@code 2.9 x 2.9} hitbox.
 *
 * <p>DragonMineZ is a mandatory dependency, so the animation path always resolves; the SU texture ships in this addon.
 * The star is clamped to the shipped 1..7 range before it is folded into the texture path, so a bad synced value
 * degrades to a valid texture rather than a broken {@link ResourceLocation}.
 */
public class SuperBallModel extends GeoModel<SuperBallEntity>
{
    private static final ResourceLocation GEO =
            new ResourceLocation("dmz_ragnarok", "geo/block/dball_super4x.geo.json");
    private static final ResourceLocation ANIM =
            new ResourceLocation("dragonminez", "animations/block/dball.animation.json");

    @Override
    public ResourceLocation getModelResource(SuperBallEntity animatable)
    {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(SuperBallEntity animatable)
    {
        int star = Mth.clamp(animatable.getStar(), 1, SuperPlanetPositions.COUNT);
        return new ResourceLocation("dmz_ragnarok", "textures/block/custom/dballblock_super" + star + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(SuperBallEntity animatable)
    {
        return ANIM;
    }
}
