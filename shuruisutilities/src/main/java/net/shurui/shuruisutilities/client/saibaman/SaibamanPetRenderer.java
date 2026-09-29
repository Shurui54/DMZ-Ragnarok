package net.shurui.shuruisutilities.client.saibaman;

import net.minecraft.client.renderer.entity.EntityRendererProvider;

import software.bernie.geckolib.renderer.GeoEntityRenderer;

import net.shurui.shuruisutilities.saibaman.SaibamanPetEntity;

/**
 * Standard GeckoLib renderer for the {@link SaibamanPetEntity} over {@link SaibamanPetModel}. Registering this
 * SU-owned renderer is what keeps DragonMineZ's saga renderer off the entity, so it draws the reused saibaman art
 * through our own model instead.
 */
public class SaibamanPetRenderer extends GeoEntityRenderer<SaibamanPetEntity>
{
    public SaibamanPetRenderer(EntityRendererProvider.Context context)
    {
        super(context, new SaibamanPetModel());
        this.shadowRadius = 0.4F;
    }
}
