package net.shurui.dev.sdu.saga;

import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestRegistry;
import com.dragonminez.common.quest.QuestService;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Name-based TALK_TO objectives (addon feature): a TALK_TO objective whose
 * {@link SagaData.Objective#npcName} is set is also completed by talking to any living entity with that
 * display name (a Custom NPC, an sdu fighter, even a name-tagged mob), not just DMZ's own quest NPC
 * (npcId). Rebuilt from the saga/side-quest files whenever they change (mirrors
 * {@link DeferredSpawnRegistry}); quest keys match DMZ's runtime quest ids ({@code "<sagaId>:<questId>"}
 * for saga quests, the string id for side quests).
 */
public final class TalkNpcRegistry {

    /** quest key -> (objective index -> normalized target name). */
    private static final Map<String, Map<Integer, String>> TARGETS = new HashMap<>();

    private TalkNpcRegistry() {
    }

    public static synchronized void loadFrom(MinecraftServer server) {
        TARGETS.clear();
        for (SagaData saga : SagaFileManager.loadAll(server)) {
            for (SagaData.Quest q : saga.quests) {
                index(saga.id + ":" + q.id, q.objectives);
            }
        }
        for (SideQuestData sq : SideQuestFileManager.loadAll(server)) {
            index(sq.id, sq.objectives);
        }
        if (!TARGETS.isEmpty()) {
            DmzNpc.LOGGER.info("[{}] Loaded {} quest(s) with name-based talk objectives", DmzNpc.MODID, TARGETS.size());
        }
    }

    private static void index(String questKey, List<SagaData.Objective> objectives) {
        for (int i = 0; i < objectives.size(); i++) {
            SagaData.Objective o = objectives.get(i);
            if ("TALK_TO".equals(o.type) && o.npcName != null && !o.npcName.isBlank()) {
                TARGETS.computeIfAbsent(questKey, k -> new HashMap<>()).put(i, normalize(o.npcName));
            }
        }
    }

    /** Display names compare case-insensitively with colour codes stripped, so "&6Old Kai" matches "old kai". */
    private static String normalize(String name) {
        String s = ChatFormatting.stripFormatting(name);
        return (s == null ? "" : s).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * A player right-clicked a living entity: complete any accepted, incomplete TALK_TO objectives whose
     * configured name matches the entity's display name. Progress is written through DMZ's own quest data
     * and synced, so the quest HUD/tree update exactly as if DMZ had credited it.
     */
    public static synchronized void onTalk(ServerPlayer player, LivingEntity target) {
        if (TARGETS.isEmpty()) {
            return;
        }
        String name = normalize(target.getDisplayName().getString());
        if (name.isEmpty()) {
            return;
        }
        StatsData stats = DmzForms.stats(player);
        if (stats == null) {
            return;
        }
        PlayerQuestData qd = stats.getPlayerQuestData();
        if (qd == null || qd.getAcceptedQuestIds() == null) {
            return;
        }
        boolean changed = false;
        for (String questId : qd.getAcceptedQuestIds()) {
            if (qd.isQuestCompleted(questId)) {
                continue;
            }
            Map<Integer, String> byIndex = TARGETS.get(questId);
            if (byIndex == null) {
                continue;
            }
            Quest quest = QuestRegistry.getQuest(questId);
            if (quest == null) {
                continue;
            }
            for (Map.Entry<Integer, String> e : byIndex.entrySet()) {
                if (!e.getValue().equals(name)) {
                    continue;
                }
                int idx = e.getKey();
                int required;
                try {
                    required = Math.max(1, quest.getObjectiveRequired(qd, questId, idx));
                } catch (Throwable t) {
                    required = 1;
                }
                if (qd.getObjectiveProgress(questId, idx) >= required) {
                    continue; // already talked to them
                }
                qd.setObjectiveProgress(questId, idx, required);
                changed = true;
            }
        }
        if (changed) {
            QuestService.syncQuestState(player);
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.quest.objective_complete"), true);
        }
    }
}
