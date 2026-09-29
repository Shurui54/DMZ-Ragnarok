package net.shurui.shuruisutilities.core.mixin.client.dmz;

import com.dragonminez.client.model.DMZPlayerModel;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.model.client.ModelClientCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The LOOK-ONLY {@code /model} override: DragonMineZ's own player model resolves the chosen model's geo, texture AND
 * animation instead of the player's race model. Hitbox and stats are untouched (render-only).
 *
 * <p>Two id shapes, both turned into resources by {@link ModelClientCache#resolve}:
 * <ul>
 *   <li>rgnpc id: geo {@code dmz_ragnarok:geo/entity/ragnarok/<geo>.geo.json}, texture
 *       {@code dmz_ragnarok:textures/entity/ragnarok/<tex>.png}, and the animation the rgnpc NPC renderer itself
 *       uses, DragonMineZ's {@code saga_base.animation.json}: the rgnpc geos are retargeted onto that saga rig
 *       (see {@code RgNpcModel}/{@code RgNpcFighterModel}), so this is what makes them animate instead of T-posing.</li>
 *   <li>{@code dmz:<path>}: a DragonMineZ NPC geo {@code dragonminez:geo/entity/<path>.geo.json} with the parallel
 *       texture; its animation is left as DMZ's own (arbitrary DMZ geos are not on the saga rig).</li>
 * </ul>
 *
 * <p>The resolver answers null unless the geo is present AND baked (through {@code DmzRaceGeoGuard} and the rgnpc
 * {@code RgNpcFallback} presence check), and then none of the three redirects fire: a not-yet-streamed or unbaked
 * model draws the player normally rather than crashing the render thread. RETURN on each erased
 * {@code AbstractClientPlayer} overload (the real methods, not the GeoAnimatable bridges), parameters exactly the
 * target's plus the CIR. {@code require = 0} + {@code remap = false}.
 */
@Mixin(value = DMZPlayerModel.class, remap = false)
public abstract class DmzPlayerModelModelMixin
{
    @Inject(method = "getModelResource(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void su$modelGeo(AbstractClientPlayer player, CallbackInfoReturnable<ResourceLocation> cir)
    {
        try
        {
            ModelClientCache.Resolved r = player == null ? null : ModelClientCache.resolve(player.getUUID());
            if (r != null)
                cir.setReturnValue(r.geo());
        }
        catch (Throwable ignored)
        {
        }
    }

    @Inject(method = "getTextureResource(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void su$modelTexture(AbstractClientPlayer player, CallbackInfoReturnable<ResourceLocation> cir)
    {
        try
        {
            ModelClientCache.Resolved r = player == null ? null : ModelClientCache.resolve(player.getUUID());
            if (r != null)
                cir.setReturnValue(r.texture());
        }
        catch (Throwable ignored)
        {
        }
    }

    @Inject(method = "getAnimationResource(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void su$modelAnim(AbstractClientPlayer player, CallbackInfoReturnable<ResourceLocation> cir)
    {
        try
        {
            ModelClientCache.Resolved r = player == null ? null : ModelClientCache.resolve(player.getUUID());
            if (r != null && r.animation() != null)
                cir.setReturnValue(r.animation());
        }
        catch (Throwable ignored)
        {
        }
    }
}
