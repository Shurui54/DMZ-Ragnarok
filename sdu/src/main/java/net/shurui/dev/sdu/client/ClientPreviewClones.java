package net.shurui.dev.sdu.client;

import net.shurui.dev.sdu.compat.cnpc.CnpcPreviewConfig;

import java.util.List;
import java.util.Map;

/**
 * Client-side store of per-quest saved-NPC appearances, synced from the server
 * ({@link net.shurui.dev.sdu.network.SyncPreviewClonesPacket}). Read by the DMZ quest-GUI enemy-preview mixin
 * ({@code QuestEnemyPreviewMixin}) to dress the {@code sdu:dmz_fighter} preview like the real clone.
 *
 * <p>Keyed by DMZ's quest key ({@code "<sagaId>:<questId>"} or a side-quest id); each value is the KILL-ordered
 * config list (see {@link net.shurui.dev.sdu.saga.QuestPreviewResolver}).</p>
 */
public final class ClientPreviewClones {

    private static Map<String, List<CnpcPreviewConfig>> map = Map.of();

    private ClientPreviewClones() {
    }

    public static void set(Map<String, List<CnpcPreviewConfig>> incoming) {
        map = incoming == null ? Map.of() : incoming;
    }

    /** The clone config for KILL objective {@code killIndex} of {@code questKey}, or {@code null} if none/empty. */
    public static CnpcPreviewConfig get(String questKey, int killIndex) {
        if (questKey == null) {
            return null;
        }
        List<CnpcPreviewConfig> list = map.get(questKey);
        if (list == null || killIndex < 0 || killIndex >= list.size()) {
            return null;
        }
        CnpcPreviewConfig cfg = list.get(killIndex);
        return (cfg == null || cfg.isEmpty()) ? null : cfg;
    }
}
