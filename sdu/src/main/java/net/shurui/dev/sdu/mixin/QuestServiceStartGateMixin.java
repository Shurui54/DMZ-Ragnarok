package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestRegistry;
import com.dragonminez.common.quest.QuestService;
import com.dragonminez.common.quest.Saga;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.saga.SagaGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Server-side enforcement of the two saga unlock gates (native previous-saga completion, and the addon
// (saga,quest) gate), which DMZ evaluates CLIENT SIDE ONLY. DMZ's server start path checks only within-saga
// sequential reachability (QuestAvailabilityChecker.isSagaQuestAvailable), never that the saga is unlocked,
// so both were visual locks a modified client could ignore with QuestActionC2S(START, firstQuestKey).
//
// Single server funnel: DragonMineZ 2.2's private describeStartBlocker(player, StatsData, ResolvedQuest, positional),
// which the public startQuest(player, questKey) asks for the requester AND for every nearby party member before it
// lets them join (2.1: the private startQuest(requester, controller, ...), which 2.2 removed). A non-null answer
// refuses the start (or skips that member). startQuest is called only by QuestActionC2S START. TURN_IN
// and RESUMMON both require the quest ACCEPTED already, which can't happen once START is refused, so this
// covers them transitively. Admin StoryCommand accepts quests directly (pqd.acceptQuest), a permission-gated
// override, intentionally not touched.
//
// Gate evaluated on resolved.saga() (the saga that OWNS the quest), so it blocks EVERY quest in a locked saga,
// mirroring the client hiding the whole row. Side/branch quests carry a null resolved.saga() and are covered
// transitively: their prerequisite is completion of a quest in the locked saga, which DMZ's own availability
// check already refuses.
//
// We refuse only a FRESH start (NOT_STARTED, not completed): an ACCEPTED/COMPLETED quest is left to DMZ's
// "already active" reply, and a FAILED restart is left alone because reaching FAILED required a legitimate
// START that already passed this gate. Uses the LIVE PlayerQuestData, so a prerequisite completed on another
// shard (progress rides the vault) is seen the moment the packet lands.
//
// Refusal returns a translatable reason as the return value; the QuestActionC2S handler styles quest failures
// red and sends them, exactly as DMZ does for its own refusals. The blocker is answered before DMZ begins the quest,
// initialises objectives or spawns anything, so nothing is half-applied.
//
// SILENT-FAILURE SIGNATURE: require=0, so if DMZ renames or reshapes this method the inject binds nothing and
// the gate does not run, leaving TODAY'S client-only lock. That fails SAFE (exploit re-opened, not a crash),
// which is why it must be launch-tested, not trusted on a green build. SagaGate itself fails open, so a live
// evaluation error also degrades to no lock.
//
// remap=false.
@Mixin(value = QuestService.class, remap = false)
public abstract class QuestServiceStartGateMixin {

    @Inject(
            method = "describeStartBlocker(Lnet/minecraft/server/level/ServerPlayer;Lcom/dragonminez/common/stats/StatsData;Lcom/dragonminez/common/quest/QuestService$ResolvedQuest;Z)Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"),
            require = 0,
            cancellable = true,
            remap = false)
    private static void sdu$enforceSagaGate(ServerPlayer player, StatsData data, QuestService.ResolvedQuest resolved,
                                            boolean positional, CallbackInfoReturnable<Component> cir) {
        try {
            if (cir.getReturnValue() != null || resolved == null || data == null) {
                return; // DMZ already refuses (its reason wins), or nothing to judge
            }
            Quest quest = resolved.quest();
            Saga saga = resolved.saga();
            if (quest == null || saga == null || !quest.isSagaQuest()) {
                return;
            }
            PlayerQuestData pqd = data.getPlayerQuestData();
            if (pqd == null) {
                return; // no live progression: fail open, DMZ decides
            }
            String questKey = resolved.questKey();
            PlayerQuestData.QuestStatus status = pqd.getQuestStatus(questKey);
            if (pqd.isQuestCompleted(questKey)
                    || status == PlayerQuestData.QuestStatus.ACCEPTED
                    || status == PlayerQuestData.QuestStatus.FAILED) {
                return;
            }
            Component reason = SagaGate.describeLock(saga, pqd, QuestRegistry::getSaga);
            if (reason != null) {
                cir.setReturnValue(reason); // refuse; nothing accepted or spawned
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] server saga-gate enforcement skipped: {}", DmzNpc.MODID, t.toString());
        }
    }
}
