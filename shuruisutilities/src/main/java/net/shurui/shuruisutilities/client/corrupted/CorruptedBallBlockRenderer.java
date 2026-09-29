package net.shurui.shuruisutilities.client.corrupted;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.vertex.PoseStack;

import net.shurui.shuruisutilities.client.dball.BallRenderTypes;
import net.shurui.shuruisutilities.client.dball.DragonBallCubeGeo;
import net.shurui.shuruisutilities.client.dball.DragonBallShell;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.corrupted.CorruptedBallBlockEntity;

import software.bernie.geckolib.core.object.Color;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * Client-only renderer for the placed swap balls. Honours {@link SUConfig#dragonBallRenderStyle} exactly like the DMZ
 * ball renderer: SPHERE draws the translucent glass sphere + inner star billboard ({@link DragonBallShell}); CUBE draws
 * the corrupted ball's own faceted geo and real texture (whose crack detail and painted stars are baked in), made
 * translucent as a single layer through the depth prefill + EQUAL colour pass in {@link BallRenderTypes}, with the same
 * inner star billboard read through the glass so it matches every other set. The corrupted balls share
 * DMZ's dball geo bounds (radius 3.75/16, centre 3.375/16 in blocks) and are the deep blue of their texture with dark
 * pips. Implements {@link BlockEntityRenderer} directly and holds a small {@link GeoBlockRenderer} for the cube path.
 */
public final class CorruptedBallBlockRenderer implements BlockEntityRenderer<CorruptedBallBlockEntity>
{
    // the deep blue the corrupted ball texture is painted in, and its geo bounds mapped to blocks.
    private static final int CORRUPTED_RGB = 0x004F93;
    private static final float RADIUS = 3.75F / 16.0F;
    private static final float CENTER_Y = 3.375F / 16.0F;

    private final Geo geo;

    public CorruptedBallBlockRenderer(BlockEntityRendererProvider.Context context)
    {
        this.geo = new Geo();
    }

    @Override
    public void render(CorruptedBallBlockEntity ball, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay)
    {
        if (SUConfig.dragonBallRenderStyle == SUConfig.DragonBallRenderStyle.CUBE)
        {
            renderCube(ball, partialTick, poseStack, buffer, packedLight);
            return;
        }
        double tickCount = 0.0;
        if (Minecraft.getInstance().level != null)
        {
            tickCount = (double) Minecraft.getInstance().level.getGameTime() + partialTick;
        }
        DragonBallShell.render(poseStack, buffer, packedLight, tickCount,
                RADIUS, CENTER_Y, CORRUPTED_RGB, DragonBallShell.PIP_DARK, ball.getStar());
    }

    // the corrupted ball's own geo + texture, translucent and single-layer via the same two passes the DMZ balls use,
    // with the inner star billboard read through the glass to match every other set. The corrupted surface texture
    // (CorruptedBallBlockModel.getTextureResource, the single place its cube texture is chosen) still has its own painted
    // stars, so it doubles up until a starless variant is supplied there.
    private void renderCube(CorruptedBallBlockEntity ball, float partialTick, PoseStack poseStack,
                            MultiBufferSource buffer, int packedLight)
    {
        ResourceLocation texture = geo.getGeoModel().getTextureResource(ball);
        geo.setAlpha((float) SUConfig.dragonBallAlpha);

        RenderType depth = BallRenderTypes.depthPrefill(texture);
        RenderType colour = BallRenderTypes.colorEqual(texture);
        MultiBufferSource.BufferSource flushable =
                buffer instanceof MultiBufferSource.BufferSource bs ? bs : null;

        // INNER STAR FIRST, then flush, so it lands before the prefill writes the shell depth and the colour pass blends
        // over it, exactly as the DMZ ball renderer and sphere style do.
        DragonBallShell.renderInnerStars(poseStack, buffer, RADIUS, CENTER_Y, DragonBallShell.PIP_DARK, ball.getStar());
        if (flushable != null)
        {
            flushable.endBatch();
        }

        geo.defaultRender(poseStack, ball, buffer, depth, null, 0.0F, partialTick, packedLight);
        if (flushable != null)
        {
            flushable.endBatch();
        }
        geo.defaultRender(poseStack, ball, buffer, colour, null, 0.0F, partialTick, packedLight);
        if (flushable != null)
        {
            flushable.endBatch();
        }
        geo.setAlpha(1.0F);

        // RIM last, over the body depth the two passes above committed: the SINGLE convex hull of the corrupted ball's own
        // faceted geo (DragonBallCubeGeo.CORRUPTED, via DragonBallHull), enlarged and reverse-wound, so the outline is one
        // edge following the ball's silhouette. Emissive and a lighter shade of the corrupted blue, drawn with a winding
        // we control (no GeckoLib winding assumption, no GL cull flip). See DragonBallShell.renderHullRim.
        DragonBallShell.renderHullRim(poseStack, buffer, DragonBallCubeGeo.CORRUPTED, CENTER_Y, CORRUPTED_RGB);
    }

    /**
     * A GeckoLib block renderer over {@link CorruptedBallBlockModel} with a settable render alpha, used only to feed the
     * cube path's two passes. Never registered as the block's renderer itself; the outer renderer drives its
     * {@code defaultRender} directly, once per pass.
     */
    private static final class Geo extends GeoBlockRenderer<CorruptedBallBlockEntity>
    {
        private float alpha = 1.0F;

        private Geo()
        {
            super(new CorruptedBallBlockModel());
        }

        // white multiply at the given alpha for the body passes. The rim no longer draws through this geo (it is the
        // shared sphere hull in DragonBallShell), so no colour multiply is needed here.
        private void setAlpha(float alpha)
        {
            this.alpha = alpha;
        }

        @Override
        public Color getRenderColor(CorruptedBallBlockEntity animatable, float partialTick, int packedLight)
        {
            return Color.ofRGBA(1.0F, 1.0F, 1.0F, this.alpha);
        }
    }
}
