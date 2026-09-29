package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.PlayerQuestData;
import net.shurui.dev.sdu.DmzNpc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

// Closes the party-join quest-freeload exploit. DMZ's legitimate progress path (QuestEvents.updatePartyProgress)
// is acceptance-gated: it refuses to advance a member who has not personally accepted the quest, so each accepted
// member completes and claims from their OWN rewardsClaimed map. The full-state sync path skips that gate.
//
// PartyManager.syncQuestProgress(leader, member) -> member.getPlayerQuestData().mergeQuestStateFrom(leader). This
// runs on party JOIN (AcceptPartyInviteC2S -> acceptInvite -> joinLeaderParty), not only on the periodic sync, and
// also in joinPartyForFusion and disbandParty. For a quest the member has NOT started, mergeQuestStateFrom CREATES a
// fresh QuestProgress and calls QuestProgress.mergeForwardFrom, which raises status by rank (NOT_STARTED < FAILED <
// ACCEPTED < SUCCESS) with NO acceptance check, and copies objectiveProgress forward by max. So a never-started
// member who joins a leader holding a finished quest inherits it as SUCCESS. The claim path then checks nothing about
// participation (QuestService.claimRewards only needs the quest to resolve, not be NPC_ONLY, and isQuestCompleted;
// the QuestRewardClaimEvent it fires has no DMZ subscriber and no ledger backs it), so the freeloader claims rewards
// worth up to 2,500,000 TPS on this server for quests they never did.
//
// Copying rewardsClaimed forward does NOT fix this: the leader usually holds SUCCESS without having claimed yet
// (claiming is a separate action), so an empty map copied forward still lets the freeloader claim.
//
// THE RULE: at HEAD snapshot which quests the RECEIVER already had real progress on (status != NOT_STARTED); at TAIL
// remove any quest that is now SUCCESS but was NOT in that snapshot, returning it to NOT_STARTED. A quest the member
// already had progress on keeps DMZ's merge behaviour, including inheriting SUCCESS, because an ACCEPTED member
// finishing alongside their party is DMZ's intended model per updatePartyProgress. A merge that grants only ACCEPTED
// to a never-started member is also left alone: joining an in-progress quest is intended sharing.
//
// WHY FULL REMOVAL, NOT A CAP AT ACCEPTED (verified against DMZ 2.1.3 bytecode): mergeForwardFrom copies
// objectiveProgress forward by MAX, so a never-started member who inherited a finished quest has every objective
// already satisfied. QuestEvents.checkAndComplete re-completes any quest whose objectives are met UNLESS it is
// already completed or QuestService.requiresTurnInAction(quest) is true, so for a non-turn-in quest the very next
// kill/tick/interact objective event would re-complete it and re-open the exploit. Removing the entry entirely does
// not depend on that reasoning at all, and is the stronger guarantee for turn-in quests too.
//
// PUBLIC API ONLY, no @Shadow/@Accessor into DMZ's private `quests` map: getAcceptedQuestIds / getCompletedQuestIds /
// getFailedQuestIds each return exactly their own status (ACCEPTED, SUCCESS, FAILED), so their union is precisely
// "status != NOT_STARTED"; getCompletedQuestIds is exactly the SUCCESS set; resetQuest(id) is quests.remove(id) plus
// clearStartRequirementTiming(id), with no party/saga side effects, which is the removal primitive we want. This
// mixin IS PlayerQuestData, so `this` is cast back to it to call those.
//
// PER-INVOCATION STATE: the snapshot rides an added INSTANCE field, not a static, so two PlayerQuestData instances
// can never interleave. mergeQuestStateFrom is not reentrant, so one slot per instance is enough; it is nulled at
// TAIL so nothing is retained after the merge.
//
// COMMON side (listed in sdu.mixins.json "mixins", not "client"): PlayerQuestData exists on both sides but the
// exploit and the authoritative merge are server-side, and this is where a dedicated server loads it. The inject is
// idempotent and harmless if it ever runs client-side.
//
// FAIL-OPEN, like QuestServiceStartGateMixin: an exception here is swallowed and logged rather than allowed to break
// a legitimate party join. The tradeoff is deliberate: a throw during join would be worse than a single merge that
// was not stripped, but it DOES mean a failure re-opens the exploit for that one merge, so this must be launch-tested
// and not trusted on a green build.
//
// require = 0 only suppresses target-not-found: if DMZ renames or reshapes mergeQuestStateFrom the inject binds
// nothing and the exploit is re-opened (fails safe, not a crash), which is the other reason to launch-test.
//
// remap = false: a DMZ class, official names.
@Mixin(value = PlayerQuestData.class, remap = false)
public abstract class QuestPartyMergeFreeloadMixin {

    // Receiver's pre-merge "has real progress" set (status != NOT_STARTED), carried from HEAD to TAIL. Instance
    // field, not static: per PlayerQuestData, and mergeQuestStateFrom is not reentrant so one slot is enough.
    @Unique
    private Set<String> sdu$preMergeProgressed;

    @Inject(
            method = "mergeQuestStateFrom(Lcom/dragonminez/common/quest/PlayerQuestData;)V",
            at = @At("HEAD"),
            require = 0,
            remap = false)
    private void sdu$captureProgressedBeforeMerge(PlayerQuestData source, CallbackInfo ci) {
        try {
            PlayerQuestData self = (PlayerQuestData) (Object) this;
            // Union of the three per-status id sets == exactly "status != NOT_STARTED" (each accessor filters to its
            // own status). Copied into a new set so the live merge that follows cannot mutate our snapshot.
            Set<String> before = new HashSet<>();
            before.addAll(self.getAcceptedQuestIds());
            before.addAll(self.getCompletedQuestIds());
            before.addAll(self.getFailedQuestIds());
            this.sdu$preMergeProgressed = before;
        } catch (Throwable t) {
            // Leave the field null; TAIL reads a null snapshot as "unknown, strip nothing this merge" (see there).
            // The old behaviour treated null as "had nothing" and stripped EVERY inherited SUCCESS, which on an
            // arrival merge (DmzPartyArrivalImpl fires syncPartyQuestState at login) would wipe the receiver's OWN
            // legitimately completed, still-unclaimed quests, destroying their rewards: "quests reset when swapping
            // servers". Failing toward not-stripping keeps a genuine completion safe; the only cost is that one merge
            // is not de-freeloaded if this snapshot ever throws, which is re-closed on the very next party sync.
            this.sdu$preMergeProgressed = null;
            DmzNpc.LOGGER.debug("[{}] quest merge freeload-guard snapshot skipped: {}", DmzNpc.MODID, t.toString());
        }
    }

    @Inject(
            method = "mergeQuestStateFrom(Lcom/dragonminez/common/quest/PlayerQuestData;)V",
            at = @At("TAIL"),
            require = 0,
            remap = false)
    private void sdu$stripInheritedSuccess(PlayerQuestData source, CallbackInfo ci) {
        Set<String> before = this.sdu$preMergeProgressed;
        this.sdu$preMergeProgressed = null; // release the scratch slot; nothing retained past the merge
        if (before == null) {
            // The HEAD snapshot could not be taken, so we do NOT know which quests the receiver already had progress
            // on. Stripping on an empty "before" would remove EVERY quest that is SUCCESS after the merge, including
            // ones the receiver genuinely completed and simply has not claimed yet, which on the arrival merge is a
            // silent reset of a completed quest across a server hop. So strip nothing this merge and leave the state
            // exactly as DMZ merged it. This is the data-safe direction the owner asked for; a single un-stripped
            // freeload merge (only ever on a HEAD exception) is re-closed on the next party sync.
            return;
        }
        try {
            PlayerQuestData self = (PlayerQuestData) (Object) this;
            // getCompletedQuestIds() is a fresh LinkedHashSet, so calling resetQuest (which mutates the backing
            // `quests` map, not this returned set) while iterating is safe; no defensive copy needed.
            for (String questKey : self.getCompletedQuestIds()) {
                if (!before.contains(questKey)) {
                    // Inherited a finished quest the member never had any progress on: remove it outright so it goes
                    // back to NOT_STARTED. A quest they already had progress on is left exactly as DMZ merged it.
                    self.resetQuest(questKey);
                }
            }
        } catch (Throwable t) {
            // Fail-open: never break a party join. A swallowed failure here leaves this one merge un-stripped.
            DmzNpc.LOGGER.debug("[{}] quest merge freeload-guard strip skipped: {}", DmzNpc.MODID, t.toString());
        }
    }
}
