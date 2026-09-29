package net.shurui.dev.sdu.event;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.quest.QuestTimerConfig;

import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.QuestService;
import com.dragonminez.common.stats.StatsData;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime for addon TIMED QUESTS (limits in {@link QuestTimerConfig}). DMZ has no native quest timer, so
 * once a second we scan each online player's accepted quests: a timed quest first seen accepted starts a
 * countdown; if it isn't completed before the limit elapses the quest is reset (failed) and the player is
 * told. Completing or dropping the quest clears its timer, so re-accepting it starts fresh.
 *
 * <p>Timers are tracked in memory per session - relogging restarts a running timer (acceptable for v1;
 * persisting deadlines would need a capability).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class QuestTimerHandler {

    /** player UUID -> (quest key -> server-gametime deadline tick). */
    private static final Map<UUID, Map<String, Long>> DEADLINES = new ConcurrentHashMap<>();

    private QuestTimerHandler() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player) || player.tickCount % 20 != 0) {
            return; // once per second
        }
        try {
            tick(player);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] quest-timer tick failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    private static void tick(ServerPlayer player) {
        StatsData stats = DmzForms.stats(player);
        if (stats == null) {
            return;
        }
        PlayerQuestData qd = stats.getPlayerQuestData();
        if (qd == null) {
            return;
        }
        Set<String> accepted = qd.getAcceptedQuestIds();
        if (accepted == null || accepted.isEmpty()) {
            DEADLINES.remove(player.getUUID());
            return;
        }
        long now = player.level().getGameTime();
        Map<String, Long> mine = DEADLINES.computeIfAbsent(player.getUUID(), k -> new HashMap<>());

        for (String key : accepted) {
            int limit = QuestTimerConfig.get(key);
            if (limit <= 0) {
                continue;
            }
            if (qd.isQuestCompleted(key)) {
                mine.remove(key);
                continue;
            }
            Long deadline = mine.get(key);
            if (deadline == null) {
                mine.put(key, now + (long) limit * 20L);
                player.displayClientMessage(
                        Component.translatable("message.dmz_ragnarok.npc.quest.timed_start", limit), true);
            } else if (now >= deadline) {
                mine.remove(key);
                try {
                    qd.resetQuest(key);
                    QuestService.syncQuestState(player);
                } catch (Throwable ignored) {
                }
                player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.quest.timed_failed"), false);
            }
        }
        // Forget timers for quests that are no longer accepted (abandoned / reset elsewhere).
        mine.keySet().removeIf(k -> !accepted.contains(k));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        DEADLINES.remove(event.getEntity().getUUID());
    }
}
