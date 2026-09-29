package net.shurui.shuruisutilities.client.space;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;

/**
 * GeoModel for any SU planet saiyan drawn as a DMZ custom character, driven entirely by the {@link SaiyanAppearance}
 * contract rather than a concrete entity class, so one model serves the hostile garrison saiyan, the passive town
 * citizen and the town trader. It resolves one of DragonMineZ's own race geos from the synced gender: a MALE (or named)
 * saiyan uses {@code human.geo.json}, a FEMALE uses {@code majin_slim.geo.json} (DMZ's own choice for a female
 * human/saiyan body, and unlike bare {@code majin.geo.json} it carries the tail bones the tail layer needs). The model
 * texture is {@code null.png} (blank): the entire body is painted by the renderer's layer stack (body, face, hair, tail,
 * armor), exactly as DMZ paints a player. The saga_base animation library is served so a saga chassis's combat
 * controllers drive the shared saga rig; a passive chassis simply registers no controllers and stands.
 *
 * <p>Every DMZ geo path is a literal here, but they are DMZ's most stable ones (the human and majin race geos); a DMZ
 * move would surface as a missing-model log, and the null.png texture already degrades to an invisible body rather than
 * a crash. Resolution is deterministic on the synced fields, never on the async skin state, so the cached model never
 * flips per frame.
 */
public class PlanetSaiyanGarrisonModel<T extends Mob & GeoEntity & SaiyanAppearance> extends GeoModel<T>
{
    private static final ResourceLocation MALE_GEO =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "geo/entity/races/human.geo.json");
    private static final ResourceLocation FEMALE_GEO =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "geo/entity/races/majin_slim.geo.json");
    private static final ResourceLocation BLANK_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "textures/entity/races/null.png");
    private static final ResourceLocation ANIM_SAGA_BASE =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "animations/entity/sagas/saga_base.animation.json");

    @Override
    public ResourceLocation getModelResource(T animatable)
    {
        // pick the body geo purely by gender, so a female named NPC (e.g. Fenris) uses the female body just like a female
        // generic saiyan. The synced gender already carries each named NPC's pinned gender (see SaiyanAppearance.roll).
        return animatable.isMale() ? MALE_GEO : FEMALE_GEO;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable)
    {
        return BLANK_TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable)
    {
        return ANIM_SAGA_BASE;
    }

    /**
     * Drive the head bone from the entity's look, matching DragonMineZ's own player/saga model so the fighter tracks its
     * target. Null-guarded so a geo without a head bone simply skips this.
     */
    @Override
    public void setCustomAnimations(T animatable, long instanceId, AnimationState<T> animationState)
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
