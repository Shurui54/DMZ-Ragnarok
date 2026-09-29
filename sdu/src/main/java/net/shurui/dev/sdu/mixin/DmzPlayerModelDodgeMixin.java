package net.shurui.dev.sdu.mixin;

import com.dragonminez.client.model.DMZPlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.shurui.dev.sdu.client.DodgeAnimator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

// DMZ renders players with its own GeckoLib DMZPlayerModel (its PlayerRendererMixin cancels the vanilla
// player render), so the vanilla PlayerModel hook never runs for players. We inject the cosmetic dodge twist
// (DodgeAnimator.applyGeo) at the tail of DMZ's per-frame bone posing, rotating waist to twist the torso.
// remap=false, require=0: DMZ target; if DMZ changes the method the twist just stops, no crash.
@Mixin(value = DMZPlayerModel.class, remap = false)
public abstract class DmzPlayerModelDodgeMixin {

    @Inject(method = "setCustomAnimations(Lnet/minecraft/client/player/AbstractClientPlayer;JLsoftware/bernie/geckolib/core/animation/AnimationState;)V",
            at = @At("TAIL"), remap = false, require = 0)
    private void sdu$dodgeTwist(AbstractClientPlayer animatable, long instanceId, AnimationState<?> state,
                                CallbackInfo ci) {
        DodgeAnimator.applyGeo((GeoModel<?>) (Object) this, animatable.getId());
    }
}
