package net.shurui.dev.sdu.saga;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.cnpc.CnpcCloneSpawner;
import net.shurui.dev.sdu.compat.cnpc.CnpcPreviewConfig;
import net.shurui.dev.sdu.compat.cnpc.DmzCnpcCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves, per quest, the appearance of each saved-NPC clone bound to a KILL objective, so the DragonMineZ
 * quest-tree GUI can render its enemy preview as the <em>actual</em> Custom NPC (name + skin + model) instead of
 * a bare {@code sdu:dmz_fighter}. Built server-side (reading clone storage) and synced to clients
 * ({@link net.shurui.dev.sdu.network.SyncPreviewClonesPacket} -&gt; {@link net.shurui.dev.sdu.client.ClientPreviewClones}).
 *
 * <p>The per-quest list is in <b>KILL order</b> (one entry per KILL objective, in objective order), matching the
 * order DMZ's {@code QuestEnemyPreview} builds its {@code targets} - so the client indexes it by the preview's
 * current target index with no full-vs-KILL objective offset. Non-clone KILLs get {@link CnpcPreviewConfig#EMPTY}
 * to keep that alignment.</p>
 */
public final class QuestPreviewResolver {

    private static Map<String, List<CnpcPreviewConfig>> cache;

    private QuestPreviewResolver() {
    }

    /** Drop the cached map so the next {@link #get} rebuilds it (call after a saga/side-quest edit). */
    public static synchronized void invalidate() {
        cache = null;
    }

    public static synchronized Map<String, List<CnpcPreviewConfig>> get(MinecraftServer server) {
        if (cache == null) {
            cache = build(server);
        }
        return cache;
    }

    private static Map<String, List<CnpcPreviewConfig>> build(MinecraftServer server) {
        Map<String, List<CnpcPreviewConfig>> out = new LinkedHashMap<>();
        boolean cnpc = DmzCnpcCompat.cnpcAvailable();
        Level level = server.overworld();
        try {
            for (SagaData saga : SagaFileManager.loadAll(server)) {
                for (SagaData.Quest q : saga.quests) {
                    out.put(saga.id + ":" + q.id, killConfigs(q.objectives, level, cnpc));
                }
            }
            for (SideQuestData sq : SideQuestFileManager.loadAll(server)) {
                out.put(sq.id, killConfigs(sq.objectives, level, cnpc));
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Failed to build quest-preview clone map: {}", DmzNpc.MODID, t.toString());
        }
        DmzNpc.LOGGER.info("[{}] Built quest-preview clone configs for {} quests", DmzNpc.MODID, out.size());
        return out;
    }

    private static List<CnpcPreviewConfig> killConfigs(List<SagaData.Objective> objectives, Level level, boolean cnpc) {
        List<CnpcPreviewConfig> list = new ArrayList<>();
        for (SagaData.Objective o : objectives) {
            if (!"KILL".equals(o.type)) {
                continue;
            }
            String def = o.definition;
            if (cnpc && def != null && def.startsWith("cnpc$")) {
                list.add(CnpcCloneSpawner.readConfig(def, level));
            } else {
                list.add(CnpcPreviewConfig.EMPTY);
            }
        }
        return list;
    }
}
