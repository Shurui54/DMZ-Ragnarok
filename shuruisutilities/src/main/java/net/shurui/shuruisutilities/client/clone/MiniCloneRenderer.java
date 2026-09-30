package net.shurui.shuruisutilities.client.clone;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.clone.MiniCloneEntity;

/**
 * The single {@link EntityRenderer} registered for {@code dmz_ragnarok:mini_clone}. One entity type gets one renderer,
 * but a clone can be drawn three ways, so this dispatches per {@link MiniCloneEntity#getVariant()}: PLAYER_COPY goes to
 * a {@link MiniClonePlayerRenderer} (vanilla player model with the owner's skin and armour), while BUU and CELL_JR go
 * to a {@link MiniCloneGeoRenderer} (GeckoLib, two-layer tint). Both delegates are stock renderers that draw into the
 * bufferSource they are handed, so the vanilla green glowing-outline pass (server-applied) survives unchanged.
 */
@OnlyIn(Dist.CLIENT)
public class MiniCloneRenderer extends EntityRenderer<MiniCloneEntity>
{
    private final MiniClonePlayerRenderer<MiniCloneEntity> playerRenderer;
    private final MiniCloneGeoRenderer geoRenderer;

    public MiniCloneRenderer(EntityRendererProvider.Context context)
    {
        super(context);
        this.playerRenderer = new MiniClonePlayerRenderer<>(context, MiniCloneEntity.CLONE_SCALE);
        this.geoRenderer = new MiniCloneGeoRenderer(context);
        // The dispatcher reads THIS renderer's shadowRadius, not the delegates', so set it here.
        this.shadowRadius = 0.5F * MiniCloneEntity.CLONE_SCALE;
    }

    private EntityRenderer<MiniCloneEntity> pick(MiniCloneEntity entity)
    {
        return entity.getVariant() == MiniCloneEntity.Variant.PLAYER_COPY ? this.playerRenderer : this.geoRenderer;
    }

    @Override
    public void render(MiniCloneEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight)
    {
        pick(entity).render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(MiniCloneEntity entity)
    {
        return pick(entity).getTextureLocation(entity);
    }
}
