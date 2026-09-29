package net.shurui.shuruisutilities.timemachine.client;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.renderer.GeoEntityRenderer;

import net.shurui.shuruisutilities.timemachine.TimeMachineEntity;
import net.shurui.shuruisutilities.timemachine.TimeMachineSeat;

/**
 * Standard GeckoLib renderer for the rideable {@link TimeMachineEntity}. The geo is life-size (about 3.43 x 5.85 x
 * 3.19 blocks rendered), large for a one-seat capsule, so it is drawn at {@link TimeMachineSeat#RENDER_SCALE}; the
 * seat offset in {@code TimeMachineEntity.positionRider} is in the same world-space blocks and already accounts for it.
 */
public final class TimeMachineRenderer extends GeoEntityRenderer<TimeMachineEntity>
{
    public TimeMachineRenderer(EntityRendererProvider.Context context)
    {
        super(context, new TimeMachineModel());
        this.shadowRadius = 1.2F;
        withScale(TimeMachineSeat.RENDER_SCALE);
    }

    /**
     * The canopy is painted with partial alpha (about 1.5 percent of the texture sits between fully clear and fully
     * opaque). GeckoLib's default is {@code entityCutoutNoCull}, which is a binary alpha test: every one of those
     * pixels passes the cutoff and draws solid, so the glass reads as a painted panel. Translucent blends them
     * instead. It keeps NO_CULL like the default, so nothing else about the draw changes.
     */
    @Override
    public RenderType getRenderType(TimeMachineEntity animatable, ResourceLocation texture,
                                    MultiBufferSource bufferSource, float partialTick)
    {
        return RenderType.entityTranslucent(texture);
    }
}
