package net.shurui.dev.sdu.client.renderer;

import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.entity.PumpkinPuppetEntity;
import software.bernie.geckolib.model.GeoModel;

/** GeoModel for the pumpkin puppet minion, converted from the pack's {@code ds_pumpkin_puppet} rig. */
public class PumpkinPuppetModel extends GeoModel<PumpkinPuppetEntity> {

    private static final ResourceLocation GEO =
            ResourceLocation.parse("dmz_ragnarok:geo/entity/snipperjack/pumpkin_puppet.geo.json");
    private static final ResourceLocation TEX =
            ResourceLocation.parse("dmz_ragnarok:textures/entity/snipperjack/pumpkin_puppet.png");
    private static final ResourceLocation ANIM =
            ResourceLocation.parse("dmz_ragnarok:animations/entity/snipperjack/pumpkin_puppet.animation.json");

    @Override
    public ResourceLocation getModelResource(PumpkinPuppetEntity animatable) {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(PumpkinPuppetEntity animatable) {
        return TEX;
    }

    @Override
    public ResourceLocation getAnimationResource(PumpkinPuppetEntity animatable) {
        return ANIM;
    }
}
