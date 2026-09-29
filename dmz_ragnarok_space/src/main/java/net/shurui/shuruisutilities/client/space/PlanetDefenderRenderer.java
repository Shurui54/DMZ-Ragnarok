package net.shurui.shuruisutilities.client.space;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.space.PlanetDefenderEntity;

/**
 * Client renderer for the {@link PlanetDefenderEntity} planet-clash holder. It draws NOTHING: the holder is a pure
 * server-side anchor for the planet's answering ki wave, and the fiction is the planet firing back, not a person
 * standing in space. The visible element is the {@link com.dragonminez.common.init.entities.ki.KiWaveEntity} the
 * module casts from the holder, which DragonMineZ renders with its own beam renderer.
 *
 * <p>A renderer is still REQUIRED, because Forge looks one up for every registered entity type the moment the entity
 * is added to a client level; without one the client crashes. So this is the minimal no-op: {@link #shouldRender}
 * returns false and {@link #render} is empty, so not even a name tag is drawn. Only loads on the client.
 */
@OnlyIn(Dist.CLIENT)
public class PlanetDefenderRenderer extends EntityRenderer<PlanetDefenderEntity>
{
    public PlanetDefenderRenderer(EntityRendererProvider.Context context)
    {
        super(context);
    }

    @Override
    public boolean shouldRender(PlanetDefenderEntity entity, Frustum frustum, double camX, double camY, double camZ)
    {
        // never render, never even frustum-cull it in: the holder is invisible by design.
        return false;
    }

    @Override
    public void render(PlanetDefenderEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight)
    {
        // intentionally empty: the holder draws nothing.
    }

    @Override
    public ResourceLocation getTextureLocation(PlanetDefenderEntity entity)
    {
        // never sampled (nothing is drawn), but the abstract contract requires a value; a harmless vanilla texture.
        return new ResourceLocation("minecraft", "textures/misc/white.png");
    }
}
