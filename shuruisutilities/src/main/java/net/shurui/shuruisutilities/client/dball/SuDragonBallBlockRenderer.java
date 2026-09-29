package net.shurui.shuruisutilities.client.dball;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.vertex.PoseStack;

import com.dragonminez.client.init.blocks.renderer.DragonBallBlockRenderer;
import com.dragonminez.common.init.block.entity.DragonBallBlockEntity;

import net.shurui.shuruisutilities.core.SUConfig;

import software.bernie.geckolib.core.object.Color;

/**
 * Client-only block entity renderer for DMZ's dragon ball blocks. For the transformed sets (super, blackstar, cerulean,
 * earth, namek) it draws either a translucent glass SPHERE + inner star billboard or the ball's original faceted CUBE
 * model made translucent, per {@link SUConfig#dragonBallRenderStyle}; for every other set (anything a third addon adds)
 * it delegates to DMZ's own opaque geo so their look is untouched.
 *
 * <p>WHY re-register instead of a mixin. DMZ's {@code DragonBallBlockRenderer} does not override
 * {@code GeoBlockRenderer.render}, so a mixin would have to target the shared GeckoLib base class and gate by
 * instanceof, touching a hot library class for every geo block in the game. Re-binding the block entity type to this
 * renderer at {@code RegisterRenderers} is a plain map replacement (see {@link DragonBallClientEvents}).
 *
 * <p>CUBE style. The ball is drawn as its REAL geo and REAL texture, but translucent, with the inner star billboard read
 * through it for EVERY set (the same star that sphere style floats inside the glass). A naive translucent geo draw stacks
 * the model's internal panel faces into layer upon layer of glass, so instead the geo is drawn twice through
 * {@link BallRenderTypes}: a depth prefill then an EQUAL colour pass on the SAME shader, leaving exactly one translucent
 * layer. The per-pass alpha comes from {@link Translucent#getRenderColor}; the geometry is GeckoLib's, drawn through
 * {@code defaultRender} so the block's own translate/rotate/animation is applied identically in both passes. Sets whose
 * surface texture also carries painted stars (earth, namek) therefore double up until a starless {@code cubeTexture} is
 * pointed at them on {@link DragonBallSets.Look}.
 */
public final class SuDragonBallBlockRenderer implements BlockEntityRenderer<DragonBallBlockEntity>
{
    private final Translucent dmz;

    public SuDragonBallBlockRenderer(BlockEntityRendererProvider.Context context)
    {
        this.dmz = new Translucent(context);
    }

    @Override
    public void render(DragonBallBlockEntity ball, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay)
    {
        String setId = ball.getBallSetId();
        DragonBallSets.Look look = DragonBallSets.lookFor(setId);
        if (look != null)
        {
            if (SUConfig.dragonBallRenderStyle == SUConfig.DragonBallRenderStyle.CUBE)
            {
                renderCube(ball, look, partialTick, poseStack, buffer, packedLight);
                return;
            }
            double tickCount = 0.0;
            if (Minecraft.getInstance().level != null)
            {
                tickCount = (double) Minecraft.getInstance().level.getGameTime() + partialTick;
            }
            int stars = ball.getBallType().getStars();
            DragonBallShell.render(poseStack, buffer, packedLight, tickCount,
                    look.radius(), look.centerY(), look.baseRgb(), look.pip(), stars);
            return;
        }
        // any set we do not transform: DMZ's own opaque geo, untouched. alpha is reset to 1 so the shared renderer draws
        // it exactly as DMZ would.
        dmz.setAlpha(1.0F);
        dmz.render(ball, partialTick, poseStack, buffer, packedLight, packedOverlay);
    }

    // draw the ball's own geo + texture translucent, single-layer, via the depth prefill then EQUAL colour pass. The
    // texture is the set's cubeTexture override when it has one, otherwise whatever DMZ's model resolves for this set and
    // star. The inner star billboard is drawn first for EVERY set so the star reads inside the glass exactly as it does
    // in sphere mode; sets whose surface texture ALSO carries painted stars (earth, namek) double up until a starless
    // cubeTexture is supplied on their DragonBallSets.Look.
    private void renderCube(DragonBallBlockEntity ball, DragonBallSets.Look look, float partialTick, PoseStack poseStack,
                            MultiBufferSource buffer, int packedLight)
    {
        int stars = ball.getBallType().getStars();
        ResourceLocation texture = look.cubeTexture() != null
                ? look.cubeTexture().apply(stars)
                : dmz.getGeoModel().getTextureResource(ball);
        dmz.setAlpha((float) SUConfig.dragonBallAlpha);

        RenderType depth = BallRenderTypes.depthPrefill(texture);
        RenderType colour = BallRenderTypes.colorEqual(texture);
        MultiBufferSource.BufferSource flushable =
                buffer instanceof MultiBufferSource.BufferSource bs ? bs : null;

        // INNER STAR FIRST for every set, then flush, so its colour lands in the framebuffer BEFORE the prefill writes
        // the shell's nearer surface depth. Drawn first, the star's own LEQUAL test runs only against the world behind
        // the ball (so a wall in front still hides it) and never against the shell depth, which does not exist yet; the
        // translucent colour pass then blends the geo over it. Flushing here is what forces that order, because a
        // BufferSource batches by render type, not by submission order.
        DragonBallShell.renderInnerStars(poseStack, buffer, look.radius(), look.centerY(), look.pip(), stars);
        if (flushable != null)
        {
            flushable.endBatch();
        }

        // pass 1: depth prefill (colour masked off). Flushed so it lands before the colour pass in the shared buffer.
        dmz.defaultRender(poseStack, ball, buffer, depth, null, 0.0F, partialTick, packedLight);
        if (flushable != null)
        {
            flushable.endBatch();
        }
        // pass 2: EQUAL colour pass, one translucent layer. Ending this batch runs the render type's clear, which
        // restores depthMask(true) and a full colour mask, so the block entity render stage's ambient state is left
        // exactly as it was found (no manual RenderSystem toggles here).
        dmz.defaultRender(poseStack, ball, buffer, colour, null, 0.0F, partialTick, packedLight);
        if (flushable != null)
        {
            flushable.endBatch();
        }
        dmz.setAlpha(1.0F);

        // RIM last, over the body depth the two passes above committed: the SINGLE convex hull of THIS set's own faceted
        // geo (DragonBallCubeGeo, via DragonBallHull), enlarged and reverse-wound, so the outline is exactly one edge
        // following the ball's silhouette instead of the per-cube edges or the oversized box the user rejected. Emissive
        // and a lighter shade of the set's body colour, drawn with a winding we control (no GeckoLib winding assumption,
        // no GL cull flip). See DragonBallShell.renderHullRim.
        DragonBallShell.renderHullRim(poseStack, buffer, look.cubeRimBoxes(), look.centerY(), look.baseRgb());
    }

    /**
     * DMZ's own dragon ball renderer with a settable render alpha. Only {@link #getRenderColor} is overridden, so the
     * geo, texture and animation resolution are all DMZ's; at alpha 1 it is byte-for-byte DMZ's opaque look (used for
     * the untransformed sets and never visible otherwise), and the cube path sets the configured shell alpha around its
     * two passes. Overriding this concrete-typed hook is safe where overriding {@code render(BlockEntity)} was not.
     */
    private static final class Translucent extends DragonBallBlockRenderer
    {
        private float alpha = 1.0F;

        private Translucent(BlockEntityRendererProvider.Context context)
        {
            super(context);
        }

        // white multiply at the given alpha for the body passes, where the texture carries the colour. The rim no longer
        // draws through this geo (it is the shared sphere hull in DragonBallShell), so no colour multiply is needed here.
        private void setAlpha(float alpha)
        {
            this.alpha = alpha;
        }

        @Override
        public Color getRenderColor(DragonBallBlockEntity animatable, float partialTick, int packedLight)
        {
            return Color.ofRGBA(1.0F, 1.0F, 1.0F, this.alpha);
        }
    }
}
