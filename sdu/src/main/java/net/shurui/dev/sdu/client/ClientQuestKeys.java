package net.shurui.dev.sdu.client;

import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestRegistry;

import java.util.Map;

/**
 * Reverse-looks-up a {@link Quest}'s DMZ registry key ({@code "<sagaId>:<questId>"} or a side-quest id) from the
 * synced client quest map. DMZ's client GUI holds {@link Quest} instances but exposes no quest-&gt;key accessor;
 * {@link QuestRegistry#getClientQuests()} maps key-&gt;quest, so we match by identity. This is the key our
 * {@link ClientPreviewClones} config map is indexed by, letting the quest GUI find the saved clone for an
 * {@code sdu:dmz_fighter} objective.
 */
public final class ClientQuestKeys {

    private ClientQuestKeys() {
    }

    public static String keyFor(Quest quest) {
        if (quest == null) {
            return null;
        }
        for (Map.Entry<String, Quest> e : QuestRegistry.getClientQuests().entrySet()) {
            if (e.getValue() == quest) {
                return e.getKey();
            }
        }
        return null;
    }
}
