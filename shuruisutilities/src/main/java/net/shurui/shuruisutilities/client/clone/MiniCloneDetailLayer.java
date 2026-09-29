package net.shurui.shuruisutilities.client.clone;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import net.shurui.shuruisutilities.clone.MiniCloneEntity;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Draws the {@code _detail} texture over the tinted base pass for a BUU or CELL_JR clone. The base model is rendered
 * with the greyscale {@code _tint} texture multiplied by the clone's tint colour ({@link MiniCloneGeoRenderer#getRenderColor});
 * this layer re-renders the same geometry with the complementary {@code _detail} texture at full white, so the parts
 * that must NOT be recoloured (Buu's pants, cape and vest; Cell Jr.'s shell, face, trim and accents) draw in their
 * authored colours over the tinted skin. The two textures are disjoint (each is transparent where the other is opaque),
 * so there is no overlap or z-fighting.
 */
@OnlyIn(Dist.CLIENT)
public class MiniCloneDetailLayer extends GeoRenderLayer<MiniCloneGeoObject>
{
    private static final ResourceLocation BUU_DETAIL =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/clone/mini_buu_detail.png");
    private static final ResourceLocation CELL_JR_DETAIL =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/clone/cell_jr_detail.png");

    public MiniCloneDetailLayer(GeoRenderer<MiniCloneGeoObject> renderer)
    {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MiniCloneGeoObject animatable, BakedGeoModel bakedModel,
                       RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                       float partialTick, int packedLight, int packedOverlay)
    {
        MiniCloneEntity entity = animatable == null ? null : animatable.getCurrent();
        boolean cellJr = entity != null && entity.getVariant() == MiniCloneEntity.Variant.CELL_JR;
        ResourceLocation detail = cellJr ? CELL_JR_DETAIL : BUU_DETAIL;

        RenderType detailType = RenderType.entityCutoutNoCull(detail);
        // Full white, so the detail art shows in its authored colours and is never tinted by getRenderColor.
        this.getRenderer().reRender(bakedModel, poseStack, bufferSource, animatable, detailType,
                bufferSource.getBuffer(detailType), partialTick, packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
    }
}
