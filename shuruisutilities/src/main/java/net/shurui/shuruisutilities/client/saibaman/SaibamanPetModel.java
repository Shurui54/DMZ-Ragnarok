package net.shurui.shuruisutilities.client.saibaman;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

import net.shurui.shuruisutilities.saibaman.SaibamanPetEntity;

/**
 * GeoModel for the {@link SaibamanPetEntity}. It reuses DragonMineZ's own saibaman geo, animation and per-variant
 * texture (the established "DMZ art on an SU entity" pattern, see PlanetGarrisonDefenderModel). Because the pet is a
 * separate SU entity type with its OWN renderer, DragonMineZ's saga renderer never touches it.
 *
 * <p>DragonMineZ is a mandatory dependency, so these paths always resolve at runtime. The lookups are still kept
 * defensive: the variant is clamped to the shipped 1..6 range before it is ever folded into a texture path, so a
 * bad synced value degrades to a valid DMZ texture rather than a broken ResourceLocation. If a future DragonMineZ
 * drop removed these assets, GeckoLib would log a missing-resource warning and draw the missing-texture model
 * rather than crash.
 */
public class SaibamanPetModel extends GeoModel<SaibamanPetEntity>
{
    private static final String DMZ = "dragonminez";

    private static final ResourceLocation GEO =
            new ResourceLocation(DMZ, "geo/entity/sagas/saga_saibaman.geo.json");
    private static final ResourceLocation ANIM =
            new ResourceLocation(DMZ, "animations/entity/sagas/saga_saibaman.animation.json");

    @Override
    public ResourceLocation getModelResource(SaibamanPetEntity animatable)
    {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(SaibamanPetEntity animatable)
    {
        int variant = Mth.clamp(animatable.getVariant(), 1, SaibamanPetEntity.VARIANT_COUNT);
        return new ResourceLocation(DMZ, "textures/entity/sagas/saga_saibaman" + variant + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(SaibamanPetEntity animatable)
    {
        return ANIM;
    }

    // drive the head bone from the entity's look so the pet tracks where it faces, matching DragonMineZ's own saga
    // model. Null-guarded: a geo without a head bone simply skips this.
    @Override
    public void setCustomAnimations(SaibamanPetEntity animatable, long instanceId,
                                    AnimationState<SaibamanPetEntity> animationState)
    {
        CoreGeoBone head = getAnimationProcessor().getBone("head");
        if (head != null && animationState != null)
        {
            EntityModelData data = animationState.getData(DataTickets.ENTITY_MODEL_DATA);
            if (data != null)
            {
                head.setRotX(data.headPitch() * ((float) Math.PI / 180F));
                head.setRotY(data.netHeadYaw() * ((float) Math.PI / 180F));
            }
        }
    }
}
