package net.shurui.shuruisutilities.client.clone;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import software.bernie.geckolib.core.object.Color;
import software.bernie.geckolib.renderer.GeoReplacedEntityRenderer;

import net.shurui.shuruisutilities.clone.MiniCloneEntity;

/**
 * GeckoLib renderer for the BUU and CELL_JR clone variants. It uses {@link GeoReplacedEntityRenderer} so the model is
 * driven by a client-only {@link MiniCloneGeoObject} proxy while the real, unchanged {@link MiniCloneEntity} supplies
 * position, rotation and the synced variant / tint fields. Everything is drawn at {@link MiniCloneEntity#CLONE_SCALE}
 * via {@code withScale}.
 *
 * <p>The two-layer tint: the base pass draws the greyscale {@code _tint} texture multiplied by
 * {@link #getRenderColor} (the clone's packed tint), and {@link MiniCloneDetailLayer} draws the untinted {@code _detail}
 * texture over it.
 */
@OnlyIn(Dist.CLIENT)
public class MiniCloneGeoRenderer extends GeoReplacedEntityRenderer<MiniCloneEntity, MiniCloneGeoObject>
{
    public MiniCloneGeoRenderer(EntityRendererProvider.Context context)
    {
        super(context, new MiniCloneGeoModel(), new MiniCloneGeoObject());
        this.shadowRadius = 0.5F * MiniCloneEntity.CLONE_SCALE;
        withScale(MiniCloneEntity.CLONE_SCALE);
        addRenderLayer(new MiniCloneDetailLayer(this));
    }

    @Override
    public void render(MiniCloneEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight)
    {
        // Bind the entity to the shared proxy so the model and tint resolve from its synced fields this pass.
        getAnimatable().setCurrent(entity);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    public Color getRenderColor(MiniCloneGeoObject animatable, float partialTick, int packedLight)
    {
        MiniCloneEntity entity = animatable == null ? null : animatable.getCurrent();
        int tint = entity == null ? 0xFFFFFF : entity.getTintColor();
        return Color.ofOpaque(tint);
    }
}
