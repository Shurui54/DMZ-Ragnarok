package net.shurui.shuruisutilities.racing.client;

import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.racing.entity.RaceSaibamanEntity;

/**
 * GeoModel for the {@link RaceSaibamanEntity} (R9), reusing DragonMineZ's own saga-saibaman geo, animation and the
 * green (variant 1) texture, exactly the art {@code SaibamanPetModel} uses. Because the race saibaman is its own
 * entity type with its own renderer, DragonMineZ's saga renderer never touches it.
 *
 * <p>DragonMineZ is a mandatory dependency, so these paths always resolve at runtime; if a future drop removed them
 * GeckoLib would draw the missing-texture model rather than crash.
 */
public class RaceSaibamanModel extends GeoModel<RaceSaibamanEntity>
{
    private static final String DMZ = "dragonminez";

    private static final ResourceLocation GEO =
            new ResourceLocation(DMZ, "geo/entity/sagas/saga_saibaman.geo.json");
    private static final ResourceLocation ANIM =
            new ResourceLocation(DMZ, "animations/entity/sagas/saga_saibaman.animation.json");
    private static final ResourceLocation TEXTURE =
            new ResourceLocation(DMZ, "textures/entity/sagas/saga_saibaman1.png");

    @Override
    public ResourceLocation getModelResource(RaceSaibamanEntity animatable)
    {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(RaceSaibamanEntity animatable)
    {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(RaceSaibamanEntity animatable)
    {
        return ANIM;
    }
}
