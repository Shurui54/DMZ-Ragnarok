package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.player.AbstractClientPlayer;

import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.client.ocarina.OcarinaPose;

/**
 * The ocarina hold (hands at the mouth) on top of DMZ's meditation pose, at the tail of {@code DMZPlayerModel}'s
 * per-frame bone posing, the same hook as the cosmetic animation poser. {@code require = 0}: a reshaped DMZ model
 * simply stops posing. Client-only.
 */
@Mixin(targets = "com.dragonminez.client.model.DMZPlayerModel", remap = false)
public abstract class MixinDmzPlayerModelOcarinaPose
{
    @Inject(method = "setCustomAnimations(Lnet/minecraft/client/player/AbstractClientPlayer;JLsoftware/bernie/geckolib/core/animation/AnimationState;)V",
            at = @At("TAIL"), remap = false, require = 0)
    private void su$ocarinaPose(AbstractClientPlayer player, long instanceId, AnimationState<?> state, CallbackInfo ci)
    {
        try
        {
            OcarinaPose.applyGeo((GeoModel<?>) (Object) this, player);
        }
        catch (Throwable ignored)
        {
            // cosmetic and per frame: never take the render thread down
        }
    }
}
