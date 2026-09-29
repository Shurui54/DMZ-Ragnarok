package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.player.AbstractClientPlayer;

import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticAnimPlayerPoser;

/**
 * Drives the REAL player through a triggered cosmetic animation. DMZ renders players with its own GeckoLib
 * {@code DMZPlayerModel} (its {@code PlayerRendererMixin} cancels the vanilla player render), so at the TAIL of that
 * model's per-frame bone posing we retarget the effect's player-joint keyframes onto the player's own bones through
 * {@link CosmeticAnimPlayerPoser}, exactly where {@code sdu}'s dodge flourish hooks. It stacks on top of DMZ's pose
 * because GeckoLib resets each bone from the animation snapshot every frame.
 *
 * <p>{@code remap = false, require = 0}: DMZ is a hard dependency so the class is present, but if a future DMZ build
 * renames or reshapes {@code setCustomAnimations} this simply stops posing (the effect FX still plays) instead of
 * crashing the client. Client-only.
 */
@Mixin(targets = "com.dragonminez.client.model.DMZPlayerModel", remap = false)
public abstract class MixinDmzPlayerModelCosmeticAnim
{
    @Inject(method = "setCustomAnimations(Lnet/minecraft/client/player/AbstractClientPlayer;JLsoftware/bernie/geckolib/core/animation/AnimationState;)V",
            at = @At("TAIL"), remap = false, require = 0)
    private void su$cosmeticAnimPose(AbstractClientPlayer player, long instanceId, AnimationState<?> state,
            CallbackInfo ci)
    {
        try
        {
            CosmeticAnimPlayerPoser.applyGeo((GeoModel<?>) (Object) this, player.getUUID(), player.getId(),
                    state.getPartialTick());
        }
        catch (Throwable ignored)
        {
            // A pose is cosmetic and per-frame: never let a bad track take the render thread down.
        }
    }
}
