package net.shurui.shuruisutilities.client.saiyan;

import java.util.List;
import java.util.function.Predicate;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Mob;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;

/**
 * The single render layer the three rgnpc renderers add to paint a generated saiyan WHEN, AND ONLY WHEN, the entity's own
 * ragnarok model is not installed. It owns the whole five-layer saiyan stack (body/face/tail, hair, vanilla armour,
 * inflated female-chest armour, scouter) and forwards to it, but ONLY when a supplied predicate reports the entity is
 * falling back.
 *
 * <h2>Why a gating wrapper rather than five bare layers</h2>
 * On a correctly installed server the common case is that the rgnpc model IS present, and the renderer is then drawing a
 * real ragnarok geo. That geo has none of the saiyan body/face/hair/armour bones, but the saiyan layers do not target
 * bones by shape, they re-render the whole model and hide sets of named bones: run against a ragnarok geo they would smear
 * the DMZ saiyan body texture, face overlays and tail over the ragnarok character. So every one of those layers MUST be
 * inert on a real model. Routing all five through this one wrapper, gated on the SAME fallback check the {@link GeoModel}
 * used to pick the geo, guarantees that in one place: when the model is present the wrapper returns immediately and none
 * of the five draw, so a present model looks exactly as it does today; when the model is absent the wrapper forwards
 * every render entry point to all five, so the saiyan is painted onto the borrowed race geo.
 *
 * <p>The wrapper forwards all three GeckoLib layer entry points, because the five layers use different ones and every one
 * matters: {@link PlanetSaiyanBodyLayer} hooks {@code render} (once, after the model), the hair / inflated-armour /
 * scouter layers hook {@code renderForBone} (per bone), and {@link PlanetSaiyanVanillaArmorLayer} (an {@code
 * ItemArmorGeoLayer}) hooks both {@code preRender} (its per-frame armour setup) and {@code renderForBone}. Missing any one
 * would drop that layer's contribution.
 *
 * <p>The five delegates are constructed with the REAL {@link GeoRenderer} (the entity renderer), not this wrapper, so each
 * delegate's {@code getRenderer()} reaches the renderer's {@code reRender} / {@code renderRecursively} helpers exactly as
 * it would if it had been added to the renderer directly.
 */
public class SaiyanFallbackLayer<T extends Mob & GeoEntity & SaiyanAppearance> extends GeoRenderLayer<T>
{
    private final Predicate<T> fallingBack;
    private final List<GeoRenderLayer<T>> delegates;

    public SaiyanFallbackLayer(GeoRenderer<T> renderer, Predicate<T> fallingBack)
    {
        super(renderer);
        this.fallingBack = fallingBack;
        // DMZ's own layer order: body/face/tail, then hair, then the two armour layers (vanilla-model set, then the
        // inflated female chest), then the scouter last so it sits over the face. A fallback rgnpc rolls SCOUTER_NONE, so
        // the scouter layer draws nothing for it; it is included for parity with the shared stack and costs nothing.
        this.delegates = List.of(
                new PlanetSaiyanBodyLayer<>(renderer),
                new PlanetSaiyanHairLayer<>(renderer),
                new PlanetSaiyanVanillaArmorLayer<>(renderer),
                new PlanetSaiyanArmorLayer<>(renderer),
                new PlanetSaiyanScouterLayer<>(renderer));
    }

    @Override
    public void preRender(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                          MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight,
                          int packedOverlay)
    {
        if (animatable == null || !this.fallingBack.test(animatable))
        {
            return;
        }
        for (GeoRenderLayer<T> delegate : this.delegates)
        {
            delegate.preRender(poseStack, animatable, bakedModel, renderType, bufferSource, buffer, partialTick,
                    packedLight, packedOverlay);
        }
    }

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel bakedModel, RenderType renderType,
                       MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight,
                       int packedOverlay)
    {
        if (animatable == null || !this.fallingBack.test(animatable))
        {
            return;
        }
        for (GeoRenderLayer<T> delegate : this.delegates)
        {
            delegate.render(poseStack, animatable, bakedModel, renderType, bufferSource, buffer, partialTick,
                    packedLight, packedOverlay);
        }
    }

    @Override
    public void renderForBone(PoseStack poseStack, T animatable, GeoBone bone, RenderType renderType,
                              MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight,
                              int packedOverlay)
    {
        if (animatable == null || !this.fallingBack.test(animatable))
        {
            return;
        }
        for (GeoRenderLayer<T> delegate : this.delegates)
        {
            delegate.renderForBone(poseStack, animatable, bone, renderType, bufferSource, buffer, partialTick,
                    packedLight, packedOverlay);
        }
    }
}
