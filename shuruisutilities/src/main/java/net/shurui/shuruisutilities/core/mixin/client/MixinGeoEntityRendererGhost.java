package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import net.shurui.shuruisutilities.client.shard.GhostPlayer;
import net.shurui.shuruisutilities.client.shard.GhostRender;

/**
 * Draws cross-server ghosts barely visible, body and every render layer alike.
 *
 * <h2>Why here, and not on the player renderer</h2>
 * DragonMineZ does not let a player go through the vanilla player renderer. Its {@code PlayerRendererMixin}
 * injects at the HEAD of {@code PlayerRenderer.render}, fetches a {@code DMZPlayerRenderer} for the player and,
 * when it gets one, draws with that and CANCELS the vanilla call. A ghost is a {@code RemotePlayer}, so it has
 * the DragonMineZ stats capability attached like any player (defaulting to human), so it always gets a
 * {@code DMZPlayerRenderer} and is always drawn through GeckoLib, never through {@code LivingEntityRenderer}.
 * That is why the earlier transparency mixin on {@code LivingEntityRenderer} did nothing: that code never runs
 * for a ghost. {@code DMZPlayerRenderer} extends {@code GeoEntityRenderer}, whose {@code render} funnels the
 * body and every layer into one {@code defaultRender} call, sharing a single {@code MultiBufferSource}.
 *
 * <h2>What this does</h2>
 * It redirects that one {@code defaultRender} call and, for {@code GhostPlayer} instances only, swaps the buffer
 * source for {@code GhostRender}'s wrapper before handing control on. From there everything the draw pulls (the
 * body, the armour layer, the held item layer, a cape) comes from a buffer that re-issues the same texture on a
 * blending render type and scales the alpha down, so the whole figure, armour included, reads as barely there.
 * See {@code GhostRender} for how the wrapper forces both translucency and low alpha.
 *
 * <h2>Safety</h2>
 * The redirect fires for every GeckoLib entity, but it only wraps the buffer when the animatable is a
 * {@code GhostPlayer}; for anything else it re-invokes {@code defaultRender} with the original buffer, byte for
 * byte the behaviour it replaced. A guard mistake here would turn every GeckoLib entity translucent, so the
 * {@code instanceof} check is the whole point. The call is re invoked from a different method than the one being
 * redirected, so there is no recursion. {@code require = 0} degrades to vanilla GeckoLib rendering if GeckoLib
 * is ever reshaped, and the wrap is wrapped so a failure there leaves the ghost solid rather than crashing.
 */
@Mixin(GeoEntityRenderer.class)
public abstract class MixinGeoEntityRendererGhost
{
    @Redirect(
            method = "render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/renderer/GeoEntityRenderer;defaultRender(Lcom/mojang/blaze3d/vertex/PoseStack;Lsoftware/bernie/geckolib/core/animatable/GeoAnimatable;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/VertexConsumer;FFI)V"),
            require = 0)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void su$ghostDefaultRender(GeoEntityRenderer self, PoseStack pose, GeoAnimatable animatable,
                                       MultiBufferSource buffers, RenderType type, VertexConsumer buffer,
                                       float partialTick, float unused, int packedLight)
    {
        MultiBufferSource source = buffers;
        try
        {
            if (animatable instanceof GhostPlayer)
                source = GhostRender.wrap(buffers);
        }
        catch (Throwable t)
        {
            source = buffers;   // A ghost drawn solid is a cosmetic miss, not a crash.
        }
        self.defaultRender(pose, animatable, source, type, buffer, partialTick, unused, packedLight);
    }
}
