package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.TrainingConfig;
import com.dragonminez.common.network.C2S.TrainingRewardC2S;
import com.dragonminez.common.stats.character.Resources;
import net.shurui.dev.sdu.DmzNpc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Make DMZ's per-minigame training cap (tpsLimitPerGame in config/dragonminez/training.json) the ABSOLUTE
// ceiling on the TP a player actually receives from ONE training-minigame reward, counting every bonus the
// suite stacks on DMZEvent.TPGainEvent: global /tpboost + shrine (TpBoostState, LOW), prestige + su.tpgain
// (PrestigeEvents), TP gems (the key's TokenBuffFeature, LOWEST), the gravity chamber (BlockFeatureEvents, LOW), plus
// DMZ's own HIGH-priority calculateTPGain rewrite in TPGainEvents.onTPGain.
//
// DMZ clamps the raw reward to the limit BEFORE any of that fires (TrainingRewardC2S.handle:
// "if (limit > 0 && totalReward > limit) totalReward = limit;"), so a 50k cap could still pay 80k once the
// multipliers ran. The reward is delivered by a single Resources.addTrainingPoints(F) call inside the
// handler's ifPresent lambda; that posts one TPGainEvent and writes oldValue + event.getTpGain() to THIS
// player's trainingPoints.
//
// We redirect that one call. Rather than chase listener order (a clamp that must land after TokenBuffFeature
// at LOWEST cannot itself rely on being LOWEST, since order within a priority is undefined), we measure this
// player's trainingPoints across the real call and shave any excess afterwards. This reads only DMZ state and
// the final value, so it needs nothing from the shuruisutilities tree. Party, fusion and gravity-chamber
// shares all land on OTHER players' Resources instances during the nested event, so they never move THIS
// player's total and are never counted against or clamped for the player who trained.
//
// remap=false: every referenced member is DMZ's own, none Minecraft. On any error we fall back to the plain
// grant so a reward can never be lost, and addTrainingPoints runs exactly once on every path (no double grant).
@Mixin(value = TrainingRewardC2S.class, remap = false)
public abstract class TrainingRewardCapMixin {

    @Shadow @Final private String minigameId;

    @Redirect(
            method = "lambda$handle$0",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/common/stats/character/Resources;addTrainingPoints(F)V"),
            remap = false,
            require = 0
    )
    private void sdu$capTrainingReward(Resources resources, float amount) {
        float limit;
        try {
            TrainingConfig.MinigameSettings settings = ConfigManager.getTrainingConfig().getSettings(this.minigameId);
            limit = settings == null ? 0f : settings.getTpsLimitPerGame();
        } catch (Throwable t) {
            // Config not readable here: behave exactly as today, no cap.
            DmzNpc.LOGGER.debug("[{}] training-reward cap skipped (settings lookup failed): {}", DmzNpc.MODID, t.toString());
            limit = 0f;
        }
        if (limit <= 0f) {
            // No cap configured for this game (0 or negative): unchanged from DMZ.
            resources.addTrainingPoints(amount);
            return;
        }
        float before = resources.getTrainingPoints();
        resources.addTrainingPoints(amount); // fires TPGainEvent -> DMZ rewrite + every suite bonus
        try {
            float gained = resources.getTrainingPoints() - before;
            if (gained > limit) {
                resources.setTrainingPoints(before + limit);
                DmzNpc.LOGGER.debug("[{}] training reward '{}' clamped {} -> {} TP (cap {})",
                        DmzNpc.MODID, this.minigameId, gained, limit, limit);
            }
        } catch (Throwable t) {
            // Clamp failed after the grant already applied: leave the reward rather than risk a double grant.
            DmzNpc.LOGGER.debug("[{}] training-reward clamp skipped after grant: {}", DmzNpc.MODID, t.toString());
        }
    }
}
