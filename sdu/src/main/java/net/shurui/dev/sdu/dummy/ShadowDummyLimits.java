package net.shurui.dev.sdu.dummy;

import com.dragonminez.common.init.entities.ShadowDummyEntity;
import com.dragonminez.common.quest.PartyManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.shurui.dev.sdu.Config;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// party-aware guard rails for DMZ shadow/training dummies, kept here so the spawn handler
// (ShadowDummySpawnLimitEvents) and RULE 3 TP handlers (ShadowDummyTpEvents) stay thin. both spawn paths
// (minigame packet, Master/Mr. Popo NPC action) stamp the owner UUID into "dmz_quest_owner" before the join,
// so the one tryAllowSpawn covers both. rules (all server-side):
//   1: per-player summon cooldown (Config.shadowDummyCooldownSeconds, 0 disables).
//   2: at most Config.shadowDummyMaxAlivePerParty alive per party. minigame path pre-clears the owner's old
//      dummy (discarded -> isAlive()==false, doesn't count) so re-summon is allowed; master path doesn't.
//   3: party killers get no TP from a dummy kill (anti party-farm), threaded via SUPPRESS_SHADOW_TP.
// everything fail-opens: on any failure allow the spawn (1/2) or leave TP alone (3).
public final class ShadowDummyLimits {

    // last successful summon (epoch ms), keyed by owner UUID.
    private static final ConcurrentHashMap<UUID, Long> LAST_SUMMON = new ConcurrentHashMap<>();

    // DMZ stamps the owner's UUID string here on both spawn paths.
    public static final String TAG_QUEST_OWNER = "dmz_quest_owner";

    // DMZ's QuestService.spawnKillObjectives stamps a non-blank string here on every kill-objective entity.
    // the minigame/master paths never write it, so a non-blank value marks a quest dummy exempt from the limits.
    public static final String TAG_QUEST_KEY = "dmz_quest_key";

    // RULE 3: set true (server thread, during LivingDeathEvent) when a party member kills a player shadow dummy,
    // so the LOWEST TPGainEvent handler zeroes the TP. ThreadLocal since death -> TP-grant runs on one thread;
    // a LOWEST death handler clears it so it can't leak.
    public static final ThreadLocal<Boolean> SUPPRESS_SHADOW_TP = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ShadowDummyLimits() {
    }

    // RULE 1 + 2 for a dummy about to join. called from the unified EntityJoinLevelEvent handler for both spawn
    // paths after the owner is resolved. denial messages the owner and the caller cancels the join; allow records
    // the cooldown and lets it spawn. owner is never null here.
    public static boolean tryAllowSpawn(ServerPlayer owner, ShadowDummyEntity joining) {
        if (owner == null) {
            return true; // fail-open
        }
        try {
            long now = System.currentTimeMillis();

            // RULE 1: per-player cooldown (0 disables). checked first, but the timestamp is only RECORDED after
            // the party check passes, so a denied party attempt doesn't reset the cooldown.
            long cooldownMs = Math.max(0L, (long) Config.shadowDummyCooldownSeconds) * 1000L;
            if (cooldownMs > 0L) {
                Long last = LAST_SUMMON.get(owner.getUUID());
                if (last != null) {
                    long elapsed = now - last;
                    if (elapsed < cooldownMs) {
                        long secondsLeft = (cooldownMs - elapsed + 999L) / 1000L;
                        owner.sendSystemMessage(Component.translatable(
                            "message.dmz_ragnarok.npc.dummy.cooldown", secondsLeft));
                        return false;
                    }
                }
            }

            // RULE 2: at most maxAlive per party, counting live dummies in the owner's level owned by a party
            // member. solo = a party of one.
            int maxAlive = Math.max(1, Config.shadowDummyMaxAlivePerParty);
            if (countPartyLiveDummies(owner, joining) >= maxAlive) {
                owner.sendSystemMessage(Component.translatable("message.dmz_ragnarok.npc.dummy.already_out"));
                return false;
            }

            LAST_SUMMON.put(owner.getUUID(), now);
            return true;
        } catch (Throwable t) {
            return true; // never break a spawn on an unexpected shape
        }
    }

    // count live dummies in the owner's level (other than joining) whose owner UUID is in the owner's party.
    // scan entities directly, not DMZ's Status, since the master/Popo path never registers there. the isAlive()
    // filter is what keeps the minigame path working: DMZ discards the old dummy before the new one joins, and a
    // discarded entity is !isAlive() so it doesn't count.
    private static int countPartyLiveDummies(ServerPlayer owner, ShadowDummyEntity joining) {
        ServerLevel level = owner.serverLevel();
        if (level == null) {
            return 0;
        }

        // every member's UUID; solo resolves to just the owner.
        Set<UUID> partyIds = new HashSet<>();
        partyIds.add(owner.getUUID());
        for (ServerPlayer member : PartyManager.getAllPartyMembers(owner)) {
            if (member != null) {
                partyIds.add(member.getUUID());
            }
        }

        int count = 0;
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof ShadowDummyEntity other)) {
                continue;
            }
            if (other == joining || !other.isAlive()) {
                continue;
            }
            // kill objectives carry a non-blank dmz_quest_key and never consume a cap slot.
            if (!other.getPersistentData().getString(TAG_QUEST_KEY).isBlank()) {
                continue;
            }
            UUID ownerId = readOwner(other);
            if (ownerId != null && partyIds.contains(ownerId)) {
                count++;
            }
        }
        return count;
    }

    // "dmz_quest_owner" UUID, null if blank/malformed.
    private static UUID readOwner(Entity dummy) {
        try {
            String s = dummy.getPersistentData().getString(TAG_QUEST_OWNER);
            if (s == null || s.isBlank()) {
                return null;
            }
            return UUID.fromString(s);
        } catch (Throwable t) {
            return null;
        }
    }

    // player shadow dummy, by type or DMZ's persistent-data flag.
    public static boolean isPlayerShadowDummy(Entity entity) {
        if (entity instanceof ShadowDummyEntity) {
            return true;
        }
        // TAG_PLAYER_SHADOW = DMZ's "dmz_player_shadow" boolean on player-summoned dummies.
        return entity != null && entity.getPersistentData()
            .getBoolean(com.dragonminez.common.network.C2S.SummonPlayerShadowDummyC2S.TAG_PLAYER_SHADOW);
    }
}
