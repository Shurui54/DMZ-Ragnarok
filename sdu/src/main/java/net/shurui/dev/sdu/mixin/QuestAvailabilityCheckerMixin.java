package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.Saga;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Enables branching saga quests. DMZ's isSagaQuestAvailable first requires isSequentiallyReachable (true only
// when the immediately preceding quest in LIST ORDER is done) before evaluating prerequisites, so two quests
// following one parent (a branch) is impossible: the later one is gated on the earlier, not its branch parent.
//
// We relax it: for any quest with explicit prerequisites (the "Branch after" editor writes a SAGA_QUEST
// prerequisite at the branch parent), bypass the sequential check so DMZ's prerequisite eval is the real gate.
// Quests with no prerequisites keep the linear behaviour.
//
// remap=false.
@Mixin(value = com.dragonminez.common.quest.QuestAvailabilityChecker.class, remap = false)
public abstract class QuestAvailabilityCheckerMixin {

    private static boolean sdu$loggedActive = false;

    @Inject(
            method = "isSequentiallyReachable(Lcom/dragonminez/common/quest/Saga;ILcom/dragonminez/common/quest/PlayerQuestData;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void sdu$branchBypassesSequential(Saga saga, int index, PlayerQuestData data,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (!sdu$loggedActive) {
            sdu$loggedActive = true;
            org.slf4j.LoggerFactory.getLogger("sdu").info(
                    "[sdu] Saga-branch mixin ACTIVE (QuestAvailabilityChecker.isSequentiallyReachable hooked).");
        }
        if (saga == null || index < 0) {
            return;
        }
        List<Quest> quests = saga.getQuests();
        if (quests == null || index >= quests.size()) {
            return;
        }
        Quest quest = quests.get(index);
        // if this quest has its own prerequisites, let those decide reachability instead of the strict
        // "previous quest in list order must be done" rule
        if (quest != null && quest.hasPrerequisites()) {
            cir.setReturnValue(true);
        }
    }
}
