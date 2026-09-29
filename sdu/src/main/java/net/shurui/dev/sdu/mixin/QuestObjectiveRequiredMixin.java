package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.QuestObjective;
import com.dragonminez.common.quest.objectives.ItemObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

// Kills party-size material inflation on item quest objectives.
//
// DMZ 2.1.3 bug: on quest start, initializeObjectiveRequirements persists a party-scaled count into
// QuestProgress.objectiveRequired (written to .dat). Item scaling is base * partyMult^(partySize-1) (~1.45
// per extra member). Submission reads that PERSISTED number back through getObjectiveRequired, never
// recomputing for the current party. Worse, party sync (mergeForwardFrom) copies the inflated value with
// putIfAbsent and never lowers it, and resetForRestart doesn't clear objectiveRequired, so the inflation
// latches forever: survives leaving the party, relog, and restart.
//
// Fix: at HEAD of getObjectiveRequired, for an ItemObjective return base getRequired() instead of the
// persisted count. Stops future inflation AND repairs affected saves. Only item objectives short-circuit;
// kill objectives fall through to DMZ's normal scaling.
//
// remap=false. require=0 so a DMZ refactor degrades to vanilla instead of crashing.
@Mixin(value = com.dragonminez.common.quest.Quest.class, remap = false)
public abstract class QuestObjectiveRequiredMixin {

    private static boolean sdu$loggedActive = false;

    @Shadow(remap = false)
    public abstract List<QuestObjective> getObjectives();

    @Inject(
            method = "getObjectiveRequired(Lcom/dragonminez/common/quest/PlayerQuestData;Ljava/lang/String;I)I",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void sdu$baseCountForItemObjectives(PlayerQuestData data, String questKey, int index,
                                                CallbackInfoReturnable<Integer> cir) {
        if (!sdu$loggedActive) {
            sdu$loggedActive = true;
            org.slf4j.LoggerFactory.getLogger("sdu").info(
                    "[sdu] Quest item-objective anti-inflation mixin ACTIVE "
                            + "(Quest.getObjectiveRequired hooked).");
        }
        List<QuestObjective> objectives = this.getObjectives();
        if (objectives == null || index < 0 || index >= objectives.size()) {
            return;
        }
        QuestObjective objective = objectives.get(index);
        if (objective instanceof ItemObjective) {
            // base unscaled requirement; ignore persisted objectiveRequired so party-inflated counts
            // (and already-latched bad values) collapse back to intended
            cir.setReturnValue(objective.getRequired());
        }
    }
}
