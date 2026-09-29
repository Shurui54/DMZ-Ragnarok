package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.Difficulty;
import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestService;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Reward exploit fix: a saga/side/repeatable quest's rewards must be the rewards for the difficulty the
// quest was COMPLETED on, not the difficulty the player happens to hold at the moment they click claim.
//
// The exploit (owner report): finish a quest on EASY/NORMAL, swap story difficulty to HARD, then claim,
// and DMZ pays the HARD tier (both the higher questRewardMultiplier AND any HARD-only reward entries).
//
// The DMZ facts (read from 2.1.3, decompiled):
//  - QuestService.claimAvailableRewards(ServerPlayer,Quest,String,PlayerQuestData) is the ONE grant site.
//    All three claim entry points funnel here: ClaimQuestRewardC2S -> claimRewards, ClaimAllQuestRewardsC2S
//    -> claimAllRewards, and the NPC turn-in QuestActionC2S(TURN_IN) -> turnInQuest -> claimRewardsForRequester,
//    all of them -> claimAvailableRewards. It reads pqd.getDifficulty() TWICE: once for the reward multiplier
//    (rewardMultiplier = pqd.getDifficulty().questRewardMultiplier()) and once for the per-reward unlock gate
//    (rewards.get(i).isUnlockedFor(pqd.getDifficulty())). getDifficulty() is the player's LIVE GLOBAL story
//    difficulty, which is exactly what the swap changes, hence the leak.
//  - DMZ already records a PER-QUEST difficulty. QuestService.startQuest does
//    pqd.setQuestDifficulty(questKey, startEvent.getDifficulty()) at accept time, the same moment it scales
//    the objectives (initializeObjectiveRequirements + spawnKillObjectives), so the recorded value IS the
//    difficulty the content was actually run and completed on. It is stored on QuestProgress, persisted in
//    the PlayerQuestData NBT (which lives in DMZ's StatsData capability), so it survives relog AND shard hops
//    (player progression rides the vault). PlayerQuestData.getQuestDifficulty(questKey) reads it back.
//  - Nothing rewrites that per-quest value on a global swap: the only ways to change global difficulty are
//    SetStoryDifficultyC2S (one-shot, guarded by difficultyChosen), and requestDifficultyReselect() (the
//    ChangeDifficultyWish dragon-ball wish, and PartyManager), none of which touch setQuestDifficulty. So the
//    recorded difficulty is frozen at start and immune to the swap. That is what makes this airtight.
//  - Party members: PlayerQuestData.QuestProgress.mergeForwardFrom copies the leader's per-quest difficulty
//    when it bumps a member's status, so a member's recorded difficulty is the party difficulty they ran, and
//    each member claims against their own pqd. Repeatables: QuestRepeatHandler.resetQuest removes the whole
//    QuestProgress, so the next accept records the difficulty afresh. Side quests start through the same
//    startQuest path. All covered by reading getQuestDifficulty at the grant.
//
// The fix: redirect both getDifficulty() reads in the grant (claimAvailableRewards) to getQuestDifficulty,
// and the one read in the turn-in availability check (hasUnclaimedRewards, used by collectNpcQuestOptions to
// decide whether an NPC offers a "claim your reward" turn-in) so the NPC's offer matches what the grant will
// actually pay. Both target methods run only after isQuestCompleted(questKey), so the QuestProgress and thus
// a real recorded difficulty always exist here.
//
// Pre-change unclaimed quests: on a 2.1.3 server every live quest was started through startQuest and carries
// a recorded difficulty, so there is effectively nothing to migrate. In the impossible case of a progress
// with no recorded value, getQuestDifficulty falls back to NORMAL, which is safe (never the swapped-to value)
// and never invents a penalty. We deliberately do NOT fall back to the current global difficulty, because the
// current global difficulty is precisely the exploit vector.
//
// remap=false (DMZ's own classes/methods). require=0 per house rule for DMZ targets: a signature drift on a
// DMZ bump disables the redirect (re-opening the pre-fix behaviour, i.e. fails to the old bug, not a crash),
// so this must be launch-tested, not trusted on a green build.
@Mixin(value = QuestService.class, remap = false)
public abstract class QuestRewardDifficultyMixin {

    // Grant site. Redirects BOTH getDifficulty() invokes (multiplier + per-reward unlock gate) to the
    // per-quest recorded difficulty. Enclosing-method args are captured after the redirect receiver.
    @Redirect(
            method = "claimAvailableRewards(Lnet/minecraft/server/level/ServerPlayer;Lcom/dragonminez/common/quest/Quest;Ljava/lang/String;Lcom/dragonminez/common/quest/PlayerQuestData;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/dragonminez/common/quest/PlayerQuestData;getDifficulty()Lcom/dragonminez/common/quest/Difficulty;"),
            require = 0,
            remap = false)
    private static Difficulty sdu$claimUsesQuestDifficulty(PlayerQuestData instance,
                                                           ServerPlayer rewardTarget, Quest quest,
                                                           String questKey, PlayerQuestData pqd) {
        return instance.getQuestDifficulty(questKey);
    }

    // Turn-in availability check, so an NPC only offers a claim the grant will honour.
    @Redirect(
            method = "hasUnclaimedRewards(Lcom/dragonminez/common/quest/PlayerQuestData;Ljava/lang/String;Lcom/dragonminez/common/quest/Quest;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/dragonminez/common/quest/PlayerQuestData;getDifficulty()Lcom/dragonminez/common/quest/Difficulty;"),
            require = 0,
            remap = false)
    private static Difficulty sdu$unclaimedUsesQuestDifficulty(PlayerQuestData instance,
                                                               PlayerQuestData pqd, String questKey,
                                                               Quest quest) {
        return instance.getQuestDifficulty(questKey);
    }
}
