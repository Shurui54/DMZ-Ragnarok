package net.shurui.dev.sdu.saga;

import com.dragonminez.common.quest.QuestService;
import com.dragonminez.common.quest.Saga;
import net.shurui.dev.sdu.DmzNpc;

/**
 * Small server-side lookup that shuruisutilities' {@code MixinDmzQuestStartRegion} calls to decide whether a
 * quest's owning saga is allowed to be started inside a {@code quest-start}-denied region. This is the ONE cross
 * addon seam for the feature: sdu is the tree the other four read from (sdu imports nothing from
 * shuruisutilities), so the lookup lives here and the mixin depends on sdu, never the reverse.
 *
 * <p>The quest key is resolved to its saga with DMZ's own {@link QuestService#resolveQuest(String)} (the exact
 * resolver {@code startQuest} runs first): a saga quest carries a non-null {@link Saga}, whose id keys the
 * per-saga toggle in {@link SagaRegionBypassConfig}. Side / branch quests carry a null saga and so are never
 * bypassed here (the ask is sagas). Fails CLOSED on any error or unknown quest: returns false, meaning "no
 * bypass, let the region flag decide", so a lookup fault can never silently open a blocked region.
 */
public final class SagaRegionBypass {

    private SagaRegionBypass() {
    }

    /**
     * @param questId the quest key handed to {@code QuestService.startQuest} / {@code resummonQuest}
     * @return true only when {@code questId} belongs to a saga whose region-bypass toggle is on
     */
    public static boolean allowsStartInBlockedRegion(String questId) {
        if (questId == null || questId.isBlank()) {
            return false;
        }
        try {
            QuestService.ResolvedQuest resolved = QuestService.resolveQuest(questId);
            if (resolved == null) {
                return false;
            }
            Saga saga = resolved.saga();
            if (saga == null || saga.getId() == null) {
                return false;
            }
            return SagaRegionBypassConfig.isAllowed(saga.getId());
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] saga region-bypass lookup skipped for '{}': {}",
                    DmzNpc.MODID, questId, t.toString());
            return false;
        }
    }
}
