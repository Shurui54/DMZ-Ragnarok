package net.shurui.dev.sdu.client.renderer;

import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.entity.SduDmzFighter;
import software.bernie.geckolib.model.GeoModel;

/**
 * GeoModel for {@link SduDmzFighter}: renders an SDU custom model but with DMZ's saga animation library, so the
 * fighter uses the full saga animation set on our own geo. The geo lives under {@code assets/sdu/geo/entity/},
 * the animations are DMZ's {@code saga_base}, and the texture is whatever the entity was configured with.
 */
public class SduDmzFighterModel extends GeoModel<SduDmzFighter> {

    private static final ResourceLocation SAGA_ANIM =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "animations/entity/sagas/saga_base.animation.json");

    @Override
    public ResourceLocation getModelResource(SduDmzFighter animatable) {
        // Full geo resloc so ANY model works (our sdu_slim/wide, or a dragonminez saga model from a clone).
        try {
            return ResourceLocation.parse(animatable.getModelGeo());
        } catch (Exception e) {
            return ResourceLocation.parse(SduDmzFighter.DEFAULT_GEO);
        }
    }

    @Override
    public ResourceLocation getTextureResource(SduDmzFighter animatable) {
        return FighterSkins.resolve(animatable);
    }

    @Override
    public ResourceLocation getAnimationResource(SduDmzFighter animatable) {
        return SAGA_ANIM;
    }
}
