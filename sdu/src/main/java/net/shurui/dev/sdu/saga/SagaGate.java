package net.shurui.dev.sdu.saga;

import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.Saga;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.DmzNpc;

import java.util.function.Function;

/**
 * The single place both sides evaluate a saga's unlock gates, so the client screen and the server can never
 * disagree. There are TWO gates, ANDed, and this mirrors DMZ's own client evaluation exactly:
 *
 * <ol>
 *   <li>The native "previous saga complete" gate, {@code Saga.SagaRequirements.previousSagaId}. DMZ evaluates
 *       it CLIENT SIDE ONLY, in {@code QuestTreeScreen.isSagaUnlockedByPreviousCompletion}; the server never
 *       checked it, so a modified client could start a gated saga's quests anyway. {@link #previousSagaComplete}
 *       is a byte-for-byte mirror of that method so the server can enforce it.</li>
 *   <li>The addon quest gate, a {@code (saga, quest)} pair held in {@link SagaQuestGateConfig}. Evaluated the
 *       same way on both sides through {@link #questGateSatisfied}.</li>
 * </ol>
 *
 * <p>Everything FAILS OPEN: a prerequisite that names a saga or quest not present in the registry (content that
 * differs between shards, or a bad reference) never keeps a saga permanently locked, matching DMZ's own
 * behaviour, where a missing previous saga returns "unlocked". A missing prerequisite is a content bug, and
 * refusing a quest the player is entitled to is far more visible than the exploit it would prevent.
 *
 * <p>The registry lookup is passed in as {@code sagaResolver} so the same rule serves both sides without this
 * class having to know which map to read: the client passes {@code QuestRegistry.getClientSagas()::get}, the
 * server passes {@code QuestRegistry::getSaga}. Quest completion is read from the LIVE {@link PlayerQuestData}
 * the caller supplies, so a prerequisite completed on another shard (quest progress rides the vault) is seen
 * the moment the packet is handled, with no assumption about where it was earned.
 */
public final class SagaGate {

    /** Refusal shown when the saga's native previous-saga gate is not satisfied. Arg %s = previous saga name. */
    public static final String LOCKED_PREV_KEY = "message.dmz_ragnarok.saga.locked_prev";
    /** Refusal shown when the addon quest gate is not satisfied. Arg %s = gating quest title. */
    public static final String LOCKED_QUEST_KEY = "message.dmz_ragnarok.saga.locked_quest";

    private SagaGate() {
    }

    /**
     * True when {@code saga}'s native previous-saga requirement is met (or there is none). A byte-for-byte
     * mirror of DMZ's client {@code QuestTreeScreen.isSagaUnlockedByPreviousCompletion}: no requirement, a
     * blank previous id, or a previous saga absent from the registry all return true (unlocked); otherwise the
     * previous saga is unlocked only when EVERY one of its quests is completed. Fails open on any exception.
     */
    public static boolean previousSagaComplete(Saga saga, PlayerQuestData pqd, Function<String, Saga> sagaResolver) {
        try {
            if (saga == null || saga.getRequirements() == null) {
                return true;
            }
            String previousSagaId = saga.getRequirements().previousSagaId();
            if (previousSagaId == null || previousSagaId.isEmpty()) {
                return true;
            }
            if (pqd == null) {
                return true; // no live progression to check: fail open, never lock on a data hiccup
            }
            Saga previousSaga = sagaResolver == null ? null : sagaResolver.apply(previousSagaId);
            if (previousSaga == null) {
                return true; // unresolved reference: fail open, exactly as DMZ does
            }
            for (Quest q : previousSaga.getQuests()) {
                if (!pqd.isQuestCompleted(PlayerQuestData.sagaQuestKey(previousSaga.getId(), q.getId()))) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] previous-saga gate check failed open: {}", DmzNpc.MODID, t.toString());
            return true;
        }
    }

    /**
     * True when {@code saga}'s addon quest gate is satisfied (or there is none). Mirrors the client quest-tree
     * gate: no gate, or a gate whose target saga / quest is absent from the registry, returns true (fail open);
     * otherwise the gate is satisfied only when the named quest is completed. Fails open on any exception.
     */
    public static boolean questGateSatisfied(Saga saga, PlayerQuestData pqd, Function<String, Saga> sagaResolver) {
        try {
            if (saga == null || saga.getId() == null) {
                return true;
            }
            SagaQuestGateConfig.Gate gate = SagaQuestGateConfig.get(saga.getId());
            if (gate == null || !gate.isValid()) {
                return true; // no quest gate on this saga
            }
            Saga target = sagaResolver == null ? null : sagaResolver.apply(gate.gateSaga);
            if (target == null || target.getQuestById(gate.gateQuest) == null) {
                return true; // unresolved reference: fail open (do not lock)
            }
            if (pqd == null) {
                return true;
            }
            String key = PlayerQuestData.sagaQuestKey(gate.gateSaga, gate.gateQuest);
            return pqd.isQuestCompleted(key);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] saga quest-gate check failed open: {}", DmzNpc.MODID, t.toString());
            return true;
        }
    }

    /**
     * The reason {@code saga} is locked for this player, or null when it is fully unlocked. Checks the native
     * previous-saga gate first, then the addon quest gate, matching the order the client shows them. The
     * returned component carries only the text; the caller (the quest-action feedback path) styles it red.
     * Fails open (returns null) on any exception, so a mismatch never refuses a legitimate start.
     */
    public static Component describeLock(Saga saga, PlayerQuestData pqd, Function<String, Saga> sagaResolver) {
        try {
            if (!previousSagaComplete(saga, pqd, sagaResolver)) {
                String previousSagaId = saga.getRequirements().previousSagaId();
                Saga previousSaga = sagaResolver == null ? null : sagaResolver.apply(previousSagaId);
                String name = previousSaga != null && previousSaga.getName() != null && !previousSaga.getName().isBlank()
                        ? previousSaga.getName() : previousSagaId;
                return Component.translatable(LOCKED_PREV_KEY, Component.literal(name));
            }
            if (!questGateSatisfied(saga, pqd, sagaResolver)) {
                SagaQuestGateConfig.Gate gate = SagaQuestGateConfig.get(saga.getId());
                Saga target = sagaResolver == null ? null : sagaResolver.apply(gate.gateSaga);
                Quest gq = target == null ? null : target.getQuestById(gate.gateQuest);
                Component questName = gq != null && gq.getTitle() != null && !gq.getTitle().isBlank()
                        ? Component.translatable(gq.getTitle()) : Component.literal("#" + gate.gateQuest);
                return Component.translatable(LOCKED_QUEST_KEY, questName);
            }
            return null;
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] saga lock description failed open: {}", DmzNpc.MODID, t.toString());
            return null;
        }
    }
}
