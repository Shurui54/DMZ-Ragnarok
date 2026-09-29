package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.rewards.TPSReward;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.util.TpMath;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

// DMZ's own saga-quest TP reward overflows before any TPGainEvent fires.
//
// TPSReward.scaledAmount(double) computes (int) Math.max(0L, Math.round(amount * rewardMultiplier)). amount is
// an int and Math.round returns a long, so the (int) cast TRUNCATES rather than saturating. The live buu saga
// rewards reach 2,000,000,000 TP, and questRewardMultiplier is 1.25 on HARD (general-server.json), so
// 2e9 * 1.25 = 2.5e9 wraps to a NEGATIVE int. giveReward then hits "if (scaled <= 0) return;" and pays NOTHING,
// so no TPGainEvent is ever posted and no suite listener can recover it. So scaledAmount must stay clamped to a
// POSITIVE int: that keeps giveReward past its early-out so the reward is actually delivered.
//
// The DOWNSTREAM cap (the reason "the multipliers don't compute" past ~2.147e9, bug #970/#978) is that the
// whole reward then funnels through int carriers: scaledAmount (int), TPGainEvent.tpGain (int) and DMZ's
// calculateTPGain (a saturating narrow to int). Every stage saturates at Integer.MAX_VALUE, and the float
// write rounds Integer.MAX_VALUE up to 2^31 = 2,147,483,648, which is exactly the gain the player observed on
// End of Z Goku's quest. DMZ stores training points as a float (Resources.trainingPoints, ceiling
// Float.MAX_VALUE), so tens of billions is a legal total; only the per-gain event is too narrow. We therefore
// ARM a parallel double accumulator here with the TRUE base*difficulty product, and ResourcesTrainingPointsWide
// + StatsDataTpSourceWide + the suite listeners multiply it in double space and write it as one float. The
// scaledAmount clamp below is unchanged: it is still the positive-gate that keeps giveReward running.
//
// remap=false: TPSReward is DMZ's own class. require=0 per house rule for DMZ targets (a signature drift
// disables the fix rather than crashing; watch for it on a DMZ bump).
@Mixin(value = TPSReward.class, remap = false)
public abstract class TpsRewardOverflowMixin {

    @Shadow @Final private int amount;

    @Inject(method = "scaledAmount(D)I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void sdu$clampScaledAmount(double rewardMultiplier, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Math.max(0, TpMath.clampToInt((double) this.amount * rewardMultiplier)));
    }

    // Arm the wide accumulator with the true (uncapped) base*difficulty gain for the addTrainingPoints call this
    // reward is about to make. Only this path arms it, so every other TP source is left on the plain int route.
    @Inject(method = "giveReward(Lnet/minecraft/server/level/ServerPlayer;D)V", at = @At("HEAD"), remap = false, require = 0)
    private void sdu$armWideReward(ServerPlayer player, double rewardMultiplier, CallbackInfo ci) {
        TpMath.armWide((double) this.amount * rewardMultiplier);
    }

    // Clear the arming if giveReward returned without delivering (no StatsData, scaled <= 0, ...), so a stale
    // armed value can never be picked up by an unrelated later gain on this thread.
    @Inject(method = "giveReward(Lnet/minecraft/server/level/ServerPlayer;D)V", at = @At("RETURN"), remap = false, require = 0)
    private void sdu$disarmWideReward(ServerPlayer player, double rewardMultiplier, CallbackInfo ci) {
        TpMath.disarmWide();
    }
}
