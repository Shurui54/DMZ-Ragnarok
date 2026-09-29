package net.shurui.shuruisutilities.timemachine.client;

import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.timemachine.TimeMachineBlockEntity;

/**
 * GeoModel for the decorative {@link TimeMachineBlockEntity}. Points at the SAME geo/texture the rideable entity uses
 * (GeckoLib's geo format is identical for a block and an entity), under the {@code shuruisutilities} asset namespace.
 */
public final class TimeMachineBlockModel extends GeoModel<TimeMachineBlockEntity>
{
    private static final ResourceLocation GEO =
            new ResourceLocation("shuruisutilities", "geo/entity/time_machine.geo.json");
    private static final ResourceLocation TEX =
            new ResourceLocation("shuruisutilities", "textures/entity/time_machine.png");
    private static final ResourceLocation ANIM =
            new ResourceLocation("shuruisutilities", "animations/entity/time_machine.animation.json");

    @Override
    public ResourceLocation getModelResource(TimeMachineBlockEntity animatable)
    {
        return GEO;
    }

    @Override
    public ResourceLocation getTextureResource(TimeMachineBlockEntity animatable)
    {
        return TEX;
    }

    @Override
    public ResourceLocation getAnimationResource(TimeMachineBlockEntity animatable)
    {
        return ANIM;
    }
}
