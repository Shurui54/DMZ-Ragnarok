package net.shurui.shuruisutilities.timemachine.client;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.renderer.GeoBlockRenderer;

import net.shurui.shuruisutilities.timemachine.TimeMachineBlockEntity;

/**
 * GeckoLib block renderer for the life-size decorative time machine. Drawn at natural scale (no shrink): the anchor
 * is the centre-bottom cell and the geo is centred/feet-at-origin, so GeoBlockRenderer's own block-centre translate
 * puts the ~3.43 x 5.85 x 3.19 block model centred over the footprint. Facing rotation is GeckoLib's default (it reads
 * HORIZONTAL_FACING), so nothing is overridden here.
 */
public final class TimeMachineBlockRenderer extends GeoBlockRenderer<TimeMachineBlockEntity>
{
    public TimeMachineBlockRenderer(BlockEntityRendererProvider.Context context)
    {
        super(new TimeMachineBlockModel());
    }

    /**
     * Same reason as {@link TimeMachineRenderer#getRenderType}: the shared texture's canopy is partial alpha, and
     * GeckoLib's default cutout render type resolves that to fully opaque. The display block and the rideable entity
     * must agree, or the same model would look glazed in one form and solid in the other.
     */
    @Override
    public RenderType getRenderType(TimeMachineBlockEntity animatable, ResourceLocation texture,
                                    MultiBufferSource bufferSource, float partialTick)
    {
        return RenderType.entityTranslucent(texture);
    }
}
