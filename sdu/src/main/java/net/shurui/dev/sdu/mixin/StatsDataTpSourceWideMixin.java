package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.TpSource;
import com.dragonminez.common.stats.StatsData;
import net.shurui.dev.sdu.util.TpMath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

// Feed DMZ's own STORY TP multiplier into the wide quest-reward accumulator (bug #970/#978).
//
// DMZ's TPGainEvents.onTPGain (HIGH priority) rewrites the gain with calculateTPGain(baseTP, STORY), which is
// (int) Math.max(0, baseTP * getTpSourceMultiplier(source)). On a big saga reward that product exceeds int, so
// DMZ saturates it to Integer.MAX_VALUE right here, before any suite multiplier gets to run. When the wide
// accumulator is active (a quest reward, armed by TpsRewardOverflowMixin), we instead multiply the accumulator
// by the SAME source multiplier in double space and return its clamped int mirror, so the story boost is
// preserved at full precision while the int event stays sensible.
//
// Off the quest path the accumulator is inactive and this injector returns immediately, so DMZ's calculateTPGain
// is untouched for kills, mining, travel and every other source.
//
// remap=false: StatsData and getTpSourceMultiplier are DMZ's own. require=0 per house rule for DMZ targets.
@Mixin(value = StatsData.class, remap = false)
public abstract class StatsDataTpSourceWideMixin {

    @Shadow public abstract double getTpSourceMultiplier(TpSource source);

    @Inject(method = "calculateTPGain(ILcom/dragonminez/common/config/TpSource;)I",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void sdu$wideTpSource(int baseTP, TpSource source, CallbackInfoReturnable<Integer> cir) {
        if (baseTP <= 0 || !TpMath.isWideActive()) {
            return;
        }
        double total = TpMath.multiplyWide(this.getTpSourceMultiplier(source));
        cir.setReturnValue(Math.max(0, TpMath.clampToInt(total)));
    }
}
