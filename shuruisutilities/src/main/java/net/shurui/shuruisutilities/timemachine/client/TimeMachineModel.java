package net.shurui.shuruisutilities.timemachine.client;

import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.timemachine.TimeMachineEntity;

/**
 * GeoModel for the rideable {@link TimeMachineEntity}. The geo and texture are the authoritative Blockbench GeckoLib
 * export (identifier {@code geometry.time_machine}), recentred by a rigid translate so the rendered model is
 * feet-at-origin and centred (see {@link net.shurui.shuruisutilities.timemachine.TimeMachineSeat}). They ship under
 * the {@code shuruisutilities} asset namespace (the naming contract's file paths); GeckoLib resolves them by these
 * explicit ResourceLocations regardless of the entity's registry namespace. The animation file is a valid-but-empty
 * clip: the model has no keyframe animations, so the controller plays nothing.
 */
public final class TimeMachineModel extends GeoModel<TimeMachineEntity>
{
    private static final ResourceLocation GEO =
            new ResourceLocation("shuruisutilities", "geo/entity/time_machine.geo.json");
    private static final ResourceLocation TEX =
            new ResourceLocation("shuruisutilities", "textures/entity/time_machine.png");
    private static final ResourceLocation ANIM =
            new ResourceLocation("shuruisutilities", "animations/entity/time_machine.animation.json");

    @Override
    public ResourceLocation getModelResource(TimeMachineEntity animatable)
    {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(TimeMachineEntity animatable)
    {
        return TEX;
    }

    @Override
    public ResourceLocation getAnimationResource(TimeMachineEntity animatable)
    {
        return ANIM;
    }
}
