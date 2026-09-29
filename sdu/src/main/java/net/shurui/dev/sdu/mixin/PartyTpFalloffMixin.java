package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.PartyManager;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.server.events.players.TPGainEvents;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Party-size linear falloff for DMZ shared-TP. shareWithParty computes one sharedTP and hands the same
// amount to every OTHER member (the earner is skipped). We scale it down past a party of 3 so a big party
// doesn't multiply free TP across members.
//
// Defaults (threshold 3, step 20%, floor 20%): size 3 -> 1.0, 4 -> 0.8, 5 -> 0.6, 6 -> 0.4, 7 -> 0.2,
// 8+ -> 0.2 (floor). threshold/step/floor read live from Config.
//
// remap=false. Null party or any error falls through to DMZ's value so a TP grant can't crash.
@Mixin(value = TPGainEvents.class, remap = false)
public abstract class PartyTpFalloffMixin {

    // runs right after `int sharedTP = (int)(tp * shareRatio)` is stored. party size includes the earner
    // (matching DMZ). scales what every teammate receives, not the earner's own TP.
    @ModifyVariable(
            method = "shareWithParty(Lnet/minecraft/server/level/ServerPlayer;Lcom/dragonminez/common/stats/StatsData;I)V",
            at = @At("STORE"),
            name = "sharedTP",
            remap = false
    )
    private static int sdu$partyTpFalloff(int sharedTP, ServerPlayer earner, StatsData data, int tp) {
        try {
            if (sharedTP <= 0 || earner == null) {
                return sharedTP;
            }
            // live from Config (percent ints -> doubles). if Config isn't populated yet (early
            // ModConfigEvent timing) the try/catch returns sharedTP unmodified.
            int threshold = Config.partyTpFalloffThreshold;
            double step = Config.partyTpFalloffStepPercent / 100.0;
            double floor = Config.partyTpFalloffFloorPercent / 100.0;

            int size = PartyManager.getAllPartyMembers(earner).size();
            if (size <= threshold) {
                return sharedTP; // party at/below threshold, leave DMZ behavior untouched
            }
            double factor = Math.max(floor, 1.0 - step * (size - threshold));
            return (int) (sharedTP * factor);
        } catch (Throwable t) {
            return sharedTP; // never break a TP grant
        }
    }
}
