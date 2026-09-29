package net.shurui.shuruisutilities.client.saiyan;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;

/**
 * SU-owned scouter layer for the town saiyans. DragonMineZ draws the scouter ONLY on its own player model, through
 * {@code DMZRacePartsLayer} (which is typed to {@code AbstractClientPlayer} and reads the wearer's {@code head_tech}
 * curio); there is NO {@code ICurioRenderer} for the scouter item, so a scouter merely placed in a slot on a mob renders
 * nothing. This layer reproduces the look on our saiyan mobs by drawing DMZ's own scouter geo model
 * ({@code dragonminez:geo/entity/scouter.geo.json}) with one of DMZ's four scouter textures
 * ({@code dragonminez:textures/entity/races/&lt;colour&gt;_scouter.png}). The colour is the synced
 * {@link SaiyanAppearance#getScouterColor()} rolled at spawn; a value of {@link SaiyanAppearance#SCOUTER_NONE} (the
 * generated-planet garrison saiyan's default) draws nothing, so adding this shared layer leaves the garrison's look
 * unchanged.
 *
 * <p>The draw copies DragonMineZ's own {@code DMZRacePartsLayer.renderScouter} transform exactly. That method fires from
 * the {@code head} bone anchor and renders ONLY the scouter geo's {@code radar} bone (the sole cube-bearing bone of the
 * four: root/waist/head are empty parents) via {@code renderRecursively} with {@code isReRender = true}, at the raw
 * head-anchor pose with NO extra pivot translate. This is DIFFERENT from {@link PlanetSaiyanHairLayer}, which translates
 * to the head pivot first: the scouter's radar cubes are authored in absolute model space (its head chain is pivoted
 * root[0,0,0] -> waist[0,12,0] -> head[0,24,0], identical to the human/majin_slim body geos these saiyans use), so a
 * second pivot translate would float it above the head. Because the body geo and the scouter geo share that head chain,
 * the radar bone lands on our saiyan's head exactly as it lands on a DMZ player's head.
 *
 * <p>{@code isReRender = true} is also the crux of why this cannot crash. {@code renderRecursively} only calls
 * {@code applyRenderLayersForBone} when {@code !isReRender}; with the flag set, walking the scouter's bones NEVER
 * re-invokes any render layer, so this layer cannot re-enter itself. The earlier version passed {@code false} and walked
 * the whole root tree, which re-fired every layer (including this one) once per scouter bone, pushing the pose stack over
 * and over until the frame ended with "Pose stack not empty". That was a wrong use of {@code renderRecursively}; drawing
 * only the {@code radar} bone with {@code isReRender = true} is DMZ's own supported path and needs no re-entrancy guard.
 *
 * <p>A plain entity cutout render type is used rather than DMZ's stencil lens shader, so the scouter shows without needing
 * DMZ's per-frame stencil setup around it. Everything is wrapped so any DMZ asset/render drift degrades to no scouter and
 * logs once, never crashing the render thread.
 */
public class PlanetSaiyanScouterLayer<T extends Mob & GeoEntity & SaiyanAppearance> extends GeoRenderLayer<T>
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean WARNED = false;

    // DMZ's shared scouter geo (root/waist/head/radar bones), shipped in the DMZ jar and loaded into the GeckoLib cache.
    private static final ResourceLocation SCOUTER_GEO =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "geo/entity/scouter.geo.json");

    public PlanetSaiyanScouterLayer(GeoRenderer<T> renderer)
    {
        super(renderer);
    }

    @Override
    public void renderForBone(PoseStack poseStack, T animatable, GeoBone bone, RenderType renderType,
                              MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay)
    {
        if (animatable == null || !bone.getName().contentEquals("head"))
        {
            return;
        }
        int colorId = animatable.getScouterColor();
        if (colorId == SaiyanAppearance.SCOUTER_NONE)
        {
            return;
        }
        String colorName = colorName(colorId);
        if (colorName == null)
        {
            return;
        }
        try
        {
            // getBakedModel is a plain cache lookup by ResourceLocation, so the saiyan renderer's own GeoModel resolves
            // DMZ's scouter geo just as well as any other loaded geo.
            BakedGeoModel scouter = this.getGeoModel().getBakedModel(SCOUTER_GEO);
            if (scouter == null)
            {
                return;
            }
            // radar is the only cube-bearing bone of the scouter geo; root/waist/head are empty parents. DMZ renders this
            // same single bone, which is why drawing it alone reproduces the whole scouter.
            GeoBone radar = scouter.getBone("radar").orElse(null);
            if (radar == null)
            {
                return;
            }
            ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                    "dragonminez", "textures/entity/races/" + colorName + "_scouter.png");
            RenderType scouterType = RenderType.entityCutoutNoCull(texture);
            VertexConsumer scouterBuffer = bufferSource.getBuffer(scouterType);

            // The pop MUST be in a finally. The catch below exists so a DMZ asset change degrades to no scouter rather
            // than crashing, but if the draw throws after the push, an un-popped pose leaves the stack unbalanced and
            // Minecraft throws "Pose stack not empty" at the end of the frame, killing the client somewhere else
            // entirely. renderRecursively balances its own push/pop, so this outer pair is purely defensive around the
            // buffer fetch and the call itself.
            poseStack.pushPose();
            try
            {
                // Draw the radar bone at the raw head-anchor pose, exactly as DMZ's renderScouter does: no
                // translateToPivotPoint (the radar cubes are authored in absolute model space), and isReRender = true so
                // applyRenderLayersForBone is skipped and no render layer, including this one, can be re-entered.
                this.getRenderer().renderRecursively(poseStack, animatable, radar, scouterType, bufferSource,
                        scouterBuffer, true, partialTick, packedLight, OverlayTexture.NO_OVERLAY,
                        1.0F, 1.0F, 1.0F, 1.0F);
            }
            finally
            {
                poseStack.popPose();
            }
        }
        catch (Throwable t)
        {
            if (!WARNED)
            {
                WARNED = true;
                LOGGER.warn("[garrison-saiyan] DragonMineZ scouter asset/render drift; town saiyans render without a scouter", t);
            }
        }
    }

    // map a synced scouter colour id to DMZ's texture/item colour name; null for an unknown id.
    private static String colorName(int colorId)
    {
        switch (colorId)
        {
            case SaiyanAppearance.SCOUTER_RED:
                return "red";
            case SaiyanAppearance.SCOUTER_BLUE:
                return "blue";
            case SaiyanAppearance.SCOUTER_GREEN:
                return "green";
            case SaiyanAppearance.SCOUTER_PURPLE:
                return "purple";
            default:
                return null;
        }
    }
}
