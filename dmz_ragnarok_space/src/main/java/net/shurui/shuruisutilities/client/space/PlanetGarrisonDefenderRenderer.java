package net.shurui.shuruisutilities.client.space;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import net.shurui.shuruisutilities.space.PlanetGarrisonDefenderEntity;
import net.shurui.shuruisutilities.client.saiyan.SaiyanFallbackLayer;
import net.shurui.shuruisutilities.client.saiyan.SaiyanFallbackRender;

/**
 * GeckoLib renderer for the {@link PlanetGarrisonDefenderEntity} wild-planet garrison fighter, over {@link
 * PlanetGarrisonDefenderModel}. Registering THIS renderer (rather than letting DragonMineZ's {@code DBSagasRenderer}
 * pick the entity up) is exactly what keeps the rgnpc art on a DragonMineZ saga entity: because the defender is a
 * separate SU entity type with its own renderer, DragonMineZ's saga renderer never touches it, and this renderer's
 * {@link PlanetGarrisonDefenderModel} serves the rgnpc geo/texture instead of a saga model.
 *
 * <p>When the rgnpc model IS installed it renders as-authored, plain (no aura / armour layers), the same way {@link
 * net.shurui.shuruisutilities.ragnarok.client.RgNpcRenderer} draws the display NPCs. The one layer added, {@link
 * SaiyanFallbackLayer}, is inert on that path; it paints a generated DragonMineZ saiyan ONLY when the model is absent and
 * {@link PlanetGarrisonDefenderModel} has borrowed a DMZ race geo (see {@link PlanetGarrisonDefenderModel#isFallback}).
 */
public class PlanetGarrisonDefenderRenderer extends GeoEntityRenderer<PlanetGarrisonDefenderEntity>
{
    public PlanetGarrisonDefenderRenderer(EntityRendererProvider.Context context)
    {
        super(context, new PlanetGarrisonDefenderModel());
        this.shadowRadius = 0.5F;
        addRenderLayer(new SaiyanFallbackLayer<>(this, PlanetGarrisonDefenderModel::isFallback));
    }

    @Override
    public void preRender(PoseStack poseStack, PlanetGarrisonDefenderEntity animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick,
                          int packedLight, int packedOverlay, float red, float green, float blue, float alpha)
    {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight,
                packedOverlay, red, green, blue, alpha);
        // Apply the shared saiyan visibility baseline only when drawing the borrowed race geo (own model absent). A real
        // ragnarok geo has none of those bones. Guarded on !isReRender because the saiyan layers hide bones per re-render
        // pass; see SaiyanFallbackRender.applyVisibilityBaseline.
        if (!isReRender && animatable != null && PlanetGarrisonDefenderModel.isFallback(animatable))
        {
            SaiyanFallbackRender.applyVisibilityBaseline(model, animatable);
        }
    }
}
