package net.shurui.shuruisutilities.racing.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Placeholder renderer for the three racing entities (item box, ki orb, Saibaman). It exists ONLY so registering
 * those entity types does not crash a client that meets one (a registered entity type with no renderer throws out
 * of the render dispatcher). It draws NOTHING: the real renderers arrive in R6 (item box), R8 (ki orbs) and R9
 * (Saibaman). No key read here; the real renderers gate on {@code ClientGate.feature("racing")}.
 */
public class RaceNoopRenderer<T extends Entity> extends EntityRenderer<T>
{
    private static final ResourceLocation TEXTURE = new ResourceLocation("dmz_ragnarok", "textures/entity/z_orb.png");

    public RaceNoopRenderer(EntityRendererProvider.Context context)
    {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(T entity)
    {
        return TEXTURE;
    }
}
