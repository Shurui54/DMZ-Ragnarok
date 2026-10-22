package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.TrainingConfig;
import com.dragonminez.common.stats.character.Resources;
import com.dragonminez.common.training.MinigameSessionManager;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

// Make DMZ's per-minigame training cap (tpsLimitPerGame in config/dragonminez/training.json) the ABSOLUTE
// ceiling on the TP a player actually receives from ONE training-minigame reward, counting every bonus the
// suite stacks on DMZEvent.TPGainEvent: global /tpboost + shrine (TpBoostState, LOW), prestige + su.tpgain
// (PrestigeEvents), TP gems (the key's TokenBuffFeature, LOWEST), the gravity chamber (BlockFeatureEvents, LOW).
//
// DragonMineZ 2.2 moved the reward server side: MinigameSessionManager.finish(ServerPlayer, Session, boolean)
// computes it (computeReward clamps to the limit BEFORE any TPGainEvent listener runs) and pays it with ONE
// Resources.addBoostedTrainingPoints(double) call, which posts one TPGainEvent (preBoosted) and writes
// old + event.getTpGain() to THIS player's training points. 2.1 did the same from TrainingRewardC2S's handler lambda,
// which 2.2 turned into a no-op.
//
// We redirect that one call. Rather than chase listener order, we measure this player's training points across the
// real call and shave any excess afterwards. Party, fusion and gravity-chamber shares land on OTHER players'
// Resources during the nested event, so they never move THIS player's total and are never clamped for them.
//
// The minigame id lives on DMZ's private Session, so the HEAD injector (Session taken as @Coerce Object) records it
// for the redirect; finish runs on the server thread and never nests. remap=false: every member is DMZ's own. On any
// error we fall back to the plain grant so a reward can never be lost, and the grant runs exactly once on every path.
@Mixin(value = MinigameSessionManager.class, remap = false)
public abstract class TrainingRewardCapMixin {

    private static String sdu$minigameId;
    private static Field sdu$minigameIdField;

    @Inject(method = "finish", at = @At("HEAD"), require = 0, remap = false)
    private static void sdu$rememberMinigame(ServerPlayer player, @Coerce Object session, boolean notify, CallbackInfo ci) {
        sdu$minigameId = null;
        try {
            if (sdu$minigameIdField == null) {
                Field f = session.getClass().getDeclaredField("minigameId");
                f.setAccessible(true);
                sdu$minigameIdField = f;
            }
            sdu$minigameId = (String) sdu$minigameIdField.get(session);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] training-reward cap could not read the minigame id: {}", DmzNpc.MODID, t.toString());
        }
    }

    @Redirect(
            method = "finish",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/common/stats/character/Resources;addBoostedTrainingPoints(D)V"),
            remap = false,
            require = 0
    )
    private static void sdu$capTrainingReward(Resources resources, double amount) {
        String minigameId = sdu$minigameId;
        sdu$minigameId = null;
        float limit;
        try {
            TrainingConfig.MinigameSettings settings = minigameId == null ? null
                    : ConfigManager.getTrainingConfig().getSettings(minigameId);
            limit = settings == null ? 0f : settings.getTpsLimitPerGame();
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] training-reward cap skipped (settings lookup failed): {}", DmzNpc.MODID, t.toString());
            limit = 0f;
        }
        if (limit <= 0f) {
            resources.addBoostedTrainingPoints(amount);
            return;
        }
        double before = resources.getTrainingPointsExact();
        resources.addBoostedTrainingPoints(amount); // fires TPGainEvent -> every suite bonus
        try {
            double gained = resources.getTrainingPointsExact() - before;
            if (gained > limit) {
                resources.setTrainingPointsExact(before + limit);
                DmzNpc.LOGGER.debug("[{}] training reward '{}' clamped {} -> {} TP (cap {})",
                        DmzNpc.MODID, minigameId, gained, limit, limit);
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] training-reward clamp skipped after grant: {}", DmzNpc.MODID, t.toString());
        }
    }
}
