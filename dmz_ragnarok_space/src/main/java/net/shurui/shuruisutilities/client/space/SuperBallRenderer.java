package net.shurui.shuruisutilities.client.space;

import net.minecraft.client.renderer.entity.EntityRendererProvider;

import software.bernie.geckolib.renderer.GeoEntityRenderer;

import net.shurui.shuruisutilities.space.SuperBallEntity;

/**
 * Standard GeckoLib renderer for the collectible {@link SuperBallEntity} over {@link SuperBallModel}. Registering this
 * SU-owned renderer is what draws the ball through our 4x geo; there is no DragonMineZ renderer competing for this
 * SU entity type. The shadow is sized to the ~2.9-block model footprint.
 */
public class SuperBallRenderer extends GeoEntityRenderer<SuperBallEntity>
{
    public SuperBallRenderer(EntityRendererProvider.Context context)
    {
        super(context, new SuperBallModel());
        this.shadowRadius = 1.4F;
    }
}
