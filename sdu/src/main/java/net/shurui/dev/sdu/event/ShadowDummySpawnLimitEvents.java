package net.shurui.dev.sdu.event;

import com.dragonminez.common.init.entities.ShadowDummyEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.dummy.ShadowDummyLimits;

import java.util.UUID;

/**
 * Unified server-side limiter for DMZ shadow / training dummies, covering BOTH spawn paths:
 * <ul>
 *   <li>the player minigame packet ({@code SummonPlayerShadowDummyC2S}), and</li>
 *   <li>the Master / Mr. Popo NPC action ({@code NPCActionC2S#handlePopo}, npcName "popo").</li>
 * </ul>
 *
 * <p>Both paths stamp the owner player's UUID into the dummy's persistentData "dmz_quest_owner"
 * BEFORE the entity joins the level, so a single {@link EntityJoinLevelEvent} handler can resolve
 * the owner and apply RULE 1 (90s cooldown) + RULE 2 (one live dummy per party) to either. This
 * replaces the old brittle DMZ-lambda mixin that only guarded the minigame path.
 *
 * <p>On a denial we cancel the join and the limiter has already messaged the owner. Everything is
 * guarded so any failure lets the spawn proceed (fail-open) rather than crashing.
 *
 * <p>CAVEAT: cancelling here, after DMZ's addFreshEntity already returned true, means DMZ may log a
 * cosmetic "spawned" line while the entity is actually removed. Acceptable (log-only).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class ShadowDummySpawnLimitEvents {

    private ShadowDummySpawnLimitEvents() {
    }

    @SubscribeEvent
    public static void onDummyJoin(EntityJoinLevelEvent event) {
        try {
            if (event.getLevel().isClientSide()) {
                return;
            }
            if (!(event.getEntity() instanceof ShadowDummyEntity dummy)) {
                return;
            }

            // Saga and side-quest kill objectives (DMZ QuestService) are stamped with dmz_quest_key.
            // Those must never be limited by the cooldown or per-party cap; only minigame and Master / Popo dummies are.
            if (!dummy.getPersistentData().getString(ShadowDummyLimits.TAG_QUEST_KEY).isBlank()) {
                return;
            }

            // Owner UUID is stamped by both spawn paths before the entity joins.
            String s = dummy.getPersistentData().getString(ShadowDummyLimits.TAG_QUEST_OWNER);
            if (s == null || s.isBlank()) {
                return;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(s);
            } catch (IllegalArgumentException malformed) {
                return;
            }

            MinecraftServer server = event.getEntity().getServer();
            if (server == null) {
                return;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(uuid);
            if (owner == null) {
                // Owner offline: allow the spawn (nobody to message, nothing to gate against).
                return;
            }

            if (!ShadowDummyLimits.tryAllowSpawn(owner, dummy)) {
                // Denied (cooldown or party already has one); owner was already messaged.
                event.setCanceled(true);
            }
        } catch (Throwable t) {
            // Never break a spawn on an unexpected shape: let it proceed as normal.
        }
    }
}
