package net.shurui.dev.sdu.mixin;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import net.shurui.dev.sdu.client.DodgeAnimator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Cosmetic dodge twist (DodgeAnimator) on top of the finished player pose. At setupAnim TAIL it stacks over
// vanilla/DMZ animation without fighting it; the animator opts out of the first-person hand render.
@Mixin(PlayerModel.class)
public abstract class PlayerModelDodgeMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void sdu$dodgeTwist(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        DodgeAnimator.apply((PlayerModel<?>) (Object) this, entity);
    }
}
