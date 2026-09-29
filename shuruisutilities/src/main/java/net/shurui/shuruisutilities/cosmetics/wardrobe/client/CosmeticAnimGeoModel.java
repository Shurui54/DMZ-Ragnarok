package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Resolves the geo, texture and {@code actived} animation for one triggered-animation rig from the bound
 * {@link CosmeticAnimAnimatable}'s rig key and direction. The converter writes each rig as
 * {@code assets/dmz_ragnarok/{geo,textures,animations}/fx/cosmetic_anim/anim_<in|out>_<key>.*}, so the key plus the
 * direction is the resource base name and one model serves all ten.
 *
 * <h2>The list-vs-read guard lives upstream</h2>
 * GeckoLib bakes from a directory LISTING and THROWS on a cache miss, on the render thread. This model never invents
 * a path a live client cannot bake because {@link CosmeticAnimationClientStore} only claims a rig slot for a key whose
 * in and out geo are actually present in {@code GeckoLibCache.getBakedModels()}; any other key falls back to particles
 * and never reaches here. So every location returned below is one that was listed and baked.
 */
@OnlyIn(Dist.CLIENT)
public class CosmeticAnimGeoModel extends GeoModel<CosmeticAnimAnimatable>
{
    private static final String NS = ShuruisUtilities.MODID;

    private static String base(CosmeticAnimAnimatable a)
    {
        return "anim_" + (a.arriving() ? "in" : "out") + "_" + a.rigKey();
    }

    @Override
    public ResourceLocation getModelResource(CosmeticAnimAnimatable a)
    {
        return new ResourceLocation(NS, "geo/fx/cosmetic_anim/" + base(a) + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(CosmeticAnimAnimatable a)
    {
        return new ResourceLocation(NS, "textures/fx/cosmetic_anim/" + base(a) + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(CosmeticAnimAnimatable a)
    {
        return new ResourceLocation(NS, "animations/fx/cosmetic_anim/" + base(a) + ".animation.json");
    }
}
