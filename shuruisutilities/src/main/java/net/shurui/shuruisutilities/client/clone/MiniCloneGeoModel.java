package net.shurui.shuruisutilities.client.clone;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.clone.MiniCloneEntity;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The GeoModel for the BUU and CELL_JR clone variants. Geometry is DragonMineZ's own saga geo (resolved at runtime
 * because dragonminez is a mandatory dependency); the texture is the SU-authored greyscale {@code _tint} layer, which
 * {@link MiniCloneGeoRenderer#getRenderColor} multiplies by the clone's tint colour. The complementary {@code _detail}
 * layer is drawn untinted on top by {@link MiniCloneDetailLayer}.
 *
 * <p>The variant is read from {@link MiniCloneGeoObject#getCurrent()}, the live entity the renderer set immediately
 * before this pass. An unset or unexpected variant falls back to the Buu geo and texture rather than a broken resource.
 */
@OnlyIn(Dist.CLIENT)
public class MiniCloneGeoModel extends GeoModel<MiniCloneGeoObject>
{
    private static final String DMZ = "dragonminez";

    private static final ResourceLocation BUU_GEO =
            new ResourceLocation(DMZ, "geo/entity/sagas/saga_buufat.geo.json");
    private static final ResourceLocation CELL_JR_GEO =
            new ResourceLocation(DMZ, "geo/entity/sagas/saga_cell_jr.geo.json");

    private static final ResourceLocation BUU_TINT =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/clone/mini_buu_tint.png");
    private static final ResourceLocation CELL_JR_TINT =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/clone/cell_jr_tint.png");

    // Never dereferenced: MiniCloneGeoObject registers no controllers, so GeckoLib never loads an animation file. Kept
    // as a stable, harmless path to satisfy the abstract contract.
    private static final ResourceLocation ANIM =
            new ResourceLocation(ShuruisUtilities.MODID, "animations/entity/clone/mini_clone.animation.json");

    private static boolean isCellJr(MiniCloneGeoObject animatable)
    {
        MiniCloneEntity entity = animatable == null ? null : animatable.getCurrent();
        return entity != null && entity.getVariant() == MiniCloneEntity.Variant.CELL_JR;
    }

    @Override
    public ResourceLocation getModelResource(MiniCloneGeoObject animatable)
    {
        return isCellJr(animatable) ? CELL_JR_GEO : BUU_GEO;
    }

    @Override
    public ResourceLocation getTextureResource(MiniCloneGeoObject animatable)
    {
        return isCellJr(animatable) ? CELL_JR_TINT : BUU_TINT;
    }

    @Override
    public ResourceLocation getAnimationResource(MiniCloneGeoObject animatable)
    {
        return ANIM;
    }
}
