package net.shurui.dev.sdu.event;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import com.dragonminez.common.quest.Difficulty;
import com.dragonminez.common.quest.PartyManager;
import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestObjective;
import com.dragonminez.common.quest.QuestRegistry;
import com.dragonminez.common.quest.QuestService;
import com.dragonminez.common.quest.objectives.KillObjective;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.quest.QuestMobOwnership;
import net.shurui.dev.sdu.saga.DeferredSpawnRegistry;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime for the addon "spawn only at quest location" KILL toggle. DMZ spawns a quest's kill NPCs when the
 * quest is accepted (via {@code QuestService.spawnKillObjectivesForQuest}); our mixin routes that through
 * {@link #onSpawnAttempt}. If the quest is location-gated (see {@link DeferredSpawnRegistry}) and the player
 * isn't at the location yet, we cancel that spawn and remember DMZ's spawn arguments in the party CONTROLLER's
 * persistent data (so it survives relog and is shared across synced-accepted party members). Once per second
 * we check each pending quest; when a member is within the COORDS radius we replay DMZ's spawn exactly, then
 * forget it.
 *
 * <p>Every actual spawn is idempotent: before replaying we scan for mobs already alive carrying DMZ's
 * {@code dmz_quest_key} tag for this quest AND owned by this player's party, and skip if a set is present.
 * Combined with keying the deferral on the party controller, a location-gated party quest spawns its kill mobs
 * exactly once no matter how many members cross the gate, how many relogs happen, or how many resummons are
 * issued. The party scope is what keeps a STRANGER running the same quest at the same spot from suppressing
 * this player's spawn, which is a dead end because DMZ will not credit them the stranger's mobs; see
 * {@link #liveQuestMobsAlive}. Non-gated quests are untouched. All new logic is guarded so any failure falls
 * through to current behavior instead of crashing.</p>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class DeferredSpawnHandler {

    /** Player-persistent compound: questId -> {count:int, diff:string}. Survives relog. */
    private static final String TAG = "sdu_deferred_spawns";

    /**
     * DMZ stamps every quest kill mob's persistentData with this quest-key tag (see
     * QuestService.spawnKillObjectives). Matching it identifies the QUEST; it does not identify whose set it
     * is, which is why {@link #liveQuestMobsAlive} additionally scopes on the owner. See that method.
     */
    private static final String DMZ_QUEST_KEY_TAG = QuestMobOwnership.TAG_QUEST_KEY;

    /** DMZ's objective-index tag on a quest kill mob, the second half of a ledger key. */
    private static final String DMZ_QUEST_OBJECTIVE_INDEX_TAG = "dmz_quest_objective_index";

    /** How far around a player DMZ scatters quest kill mobs (~10 blocks). Scan a generous box past that. */
    private static final double LIVE_SCAN_RADIUS = 96.0;

    /**
     * The heal's rare fallback scan (only on the respawn path) is as wide as DMZ's quest-mob keep range (its
     * owner cleanup discards a quest mob past ~176 blocks, {@code EntitiesEvents}), so we never add a second mob
     * when an untracked one (a lost record, a party member's mob) is still alive nearby.
     */
    private static final double DUP_GUARD_RADIUS = 200.0;

    /**
     * Per-owner PlayerPersisted sub-tag: questKey -&gt; the dimension id a replacement is owed in ("" for any). The
     * heal spawns ONLY against an entry here, once, and removes it. See {@link #healOwed} for what writes one.
     */
    private static final String OWED_TAG = "sdu_quest_mob_owed";

    /**
     * Per-viewer PlayerPersisted sub-tag holding the current kill mobs, keyed {@code "questKey|objectiveIndex"}
     * -&gt; list of mob UUID strings. Lives in {@link Player#PERSISTED_NBT_TAG} so it survives relog, respawn and
     * shard hops. It records exactly the current generation; anything not in it is a stale duplicate.
     */
    private static final String LEDGER_TAG = "sdu_quest_kill_mobs";

    /**
     * Minimum time between two replacement spawns for the same player and quest. A kill that DMZ does not credit
     * leaves the objective looking unfinished, and without a floor that reads as "the mob is gone, replace it" every
     * second, which players saw as a quest mob respawning each time they killed it. Two minutes turns any such loop
     * into an occasional respawn while the logged progress shows why the kill is not counting.
     */
    private static final long HEAL_MIN_INTERVAL_MS = 120_000L;

    /** playerUuid|questKey -&gt; when the heal last spawned a replacement. */
    private static final Map<String, Long> HEAL_LAST_SPAWN = new HashMap<>();

    /** Set while we replay a deferred spawn so our own mixin lets it through instead of re-deferring it. */
    private static volatile boolean bypass = false;

    private DeferredSpawnHandler() {
    }

    /**
     * Keeps the kill-mob ledger (see {@link #LEDGER_TAG}) and enforces one set per objective.
     *
     * <p>A FRESH spawn is recorded against its owner, unless the owner's ledger already holds as many live mobs for
     * that objective as it still needs, in which case the newcomer is refused. That case is real: DMZ's
     * {@code spawnKillObjectives} spawns for EVERY unfinished kill objective of the quest at once, so a heal replay
     * aimed at one lost mob would otherwise duplicate the others.
     *
     * <p>A mob LOADED FROM DISK that the owner's ledger does not name, while the ledger names others for the same
     * objective, is a superseded generation (the heal replaced it while its chunk was unloaded) and is refused too.
     * That is what stops piles: nothing is persistent, and nothing old can come back once something newer exists.
     * A mob whose owner is offline is left alone, because their ledger cannot be read; DMZ's own owner cleanup
     * covers those. The check is gated on a {@link Mob} carrying DMZ's objective-index tag before any other work.
     */
    @SubscribeEvent
    public static void onQuestMobJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        try {
            CompoundTag data = mob.getPersistentData();
            if (!data.contains(DMZ_QUEST_OBJECTIVE_INDEX_TAG) || !data.contains(DMZ_QUEST_KEY_TAG)) {
                return;
            }
            UUID ownerId = QuestMobOwnership.ownerOf(mob);
            MinecraftServer server = mob.getServer();
            ServerPlayer owner = ownerId == null || server == null ? null : server.getPlayerList().getPlayer(ownerId);
            if (owner == null) {
                return;
            }
            String questKey = data.getString(DMZ_QUEST_KEY_TAG);
            int index = data.getInt(DMZ_QUEST_OBJECTIVE_INDEX_TAG);
            String key = ledgerKey(questKey, index);
            List<UUID> recorded = ledgerGet(owner, key);
            if (recorded.contains(mob.getUUID())) {
                return;
            }
            if (!event.loadedFromDisk() && DeferredSpawnRegistry.deferUntilUnlocked(questKey, index)
                    && objectiveLocked(owner, questKey, index)) {
                event.setCanceled(true); // its turn has not come: spawn it once when it does
                markOwed(owner, questKey, "");
                return;
            }
            UUID predecessor = event.loadedFromDisk() ? null : transformingPredecessor(server, recorded);
            if (predecessor != null) {
                // DMZ transforms a saga mob by adding the next form BEFORE discarding the current one
                // (DBSagasEntity.finishTransformationSpawn), so the set looks full at this instant. Refusing it made
                // the boss vanish mid-transform. The new form takes over the old form's slot instead.
                ledgerRemove(owner, key, predecessor);
                ledgerAdd(owner, key, mob.getUUID());
                return;
            }
            if (event.loadedFromDisk()) {
                if (!recorded.isEmpty()) {
                    event.setCanceled(true); // a superseded generation coming back with its chunk
                }
                return;
            }
            if (aliveCount(server, recorded) >= remainingFor(owner, questKey, index)) {
                event.setCanceled(true); // this objective already has its full set
                return;
            }
            ledgerAdd(owner, key, mob.getUUID());
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] quest-mob ledger join failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    /**
     * A recorded kill mob that is killed or discarded is struck off its owner's ledger. An unload or a dimension
     * change keeps the record: the mob still exists.
     *
     * <p>Only a KILL that left the objective short (DMZ gave no credit, typically because the objective was still
     * locked) marks a replacement as owed. A DISCARD is DMZ removing the mob on purpose: its owner died, logged out,
     * changed dimension or went out of range, and DMZ's answer to all of those is the quest screen's resummon
     * button. Replacing those was what had a saga boss hunting a player down every two minutes on every shard.
     */
    @SubscribeEvent
    public static void onQuestMobLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        try {
            Entity.RemovalReason reason = mob.getRemovalReason();
            if (reason == null || !reason.shouldDestroy()) {
                return;
            }
            CompoundTag data = mob.getPersistentData();
            if (!data.contains(DMZ_QUEST_OBJECTIVE_INDEX_TAG) || !data.contains(DMZ_QUEST_KEY_TAG)) {
                return;
            }
            UUID ownerId = QuestMobOwnership.ownerOf(mob);
            MinecraftServer server = mob.getServer();
            ServerPlayer owner = ownerId == null || server == null ? null : server.getPlayerList().getPlayer(ownerId);
            if (owner == null) {
                return;
            }
            String questKey = data.getString(DMZ_QUEST_KEY_TAG);
            int index = data.getInt(DMZ_QUEST_OBJECTIVE_INDEX_TAG);
            String key = ledgerKey(questKey, index);
            ledgerRemove(owner, key, mob.getUUID());
            if (reason == Entity.RemovalReason.KILLED && !owner.isDeadOrDying()
                    && aliveCount(server, ledgerGet(owner, key)) < remainingForParty(owner, questKey, index)) {
                markOwed(owner, questKey, mob.level().dimension().location().toString());
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] quest-mob ledger leave failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    /** Called from {@code QuestServiceSpawnMixin} at the head of DMZ's kill-objective spawn. */
    public static void onSpawnAttempt(ServerPlayer player, String questId, int count, Difficulty difficulty, CallbackInfo ci) {
        try {
            if (bypass || questId == null || DeferredSpawnRegistry.isEmpty()) {
                return;
            }
            // Sequence-gated KILLs are not handled here: DMZ spawns normally and onQuestMobJoin refuses only the
            // mobs whose objective is still locked, owing them for when it unlocks. Cancelling the whole spawn
            // would also hold back the objectives that are already the player's turn.
            DeferredSpawnRegistry.Gate gate = DeferredSpawnRegistry.gateFor(questId);
            if (gate == null) {
                return; // not location-gated: let DMZ spawn normally
            }
            // Party-scope everything on the controller (leader), so synced-accepted members share one deferral.
            ServerPlayer controller = resolveController(player);
            if (gate.reached(controller)) {
                // Already at the location. Only let DMZ actually spawn if no set is alive yet, so a resummon or
                // a second party member crossing the gate never stacks another set. Clear any stale pending too.
                clearPending(controller, questId);
                if (liveQuestMobsAlive(controller, questId)) {
                    ci.cancel(); // set already present: suppress this duplicate spawn
                }
                return;
            }
            // Not there yet: remember DMZ's spawn args on the CONTROLLER so we can replay once on arrival.
            CompoundTag store = controller.getPersistentData().getCompound(TAG);
            CompoundTag entry = new CompoundTag();
            entry.putInt("count", count);
            entry.putString("diff", difficulty == null ? "" : difficulty.name());
            store.put(questId, entry);
            controller.getPersistentData().put(TAG, store);
            ci.cancel();
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] deferred-spawn check failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    /** Controller (party leader, or the player if solo/leader). Falls back to the player on any failure. */
    private static ServerPlayer resolveController(ServerPlayer player) {
        try {
            ServerPlayer controller = PartyManager.resolveQuestController(player);
            return controller != null ? controller : player;
        } catch (Throwable t) {
            return player;
        }
    }

    /**
     * True if at least one live mob for this quest, BELONGING TO THIS PLAYER'S PARTY, is already present near
     * them. Cheap: single AABB scan over living entities, only run when a spawn/replay is about to happen.
     *
     * <p>The owner scope is load-bearing, and leaving it out was a bug. This scan suppresses a spawn, and DMZ
     * only credits a quest kill when the mob's {@code dmz_quest_owner} is the killer or one of the killer's
     * current party members ({@code QuestEvents.matchesQuestSpawnTags}). Matching the quest key alone therefore
     * let a stranger's mobs, standing within {@value #LIVE_SCAN_RADIUS} blocks because they are doing the same
     * quest at the same place, suppress this player's spawn. They then arrived at the location to find an NPC
     * they could not get credit for and no NPC of their own: the quest was simply not completable while anyone
     * else was doing it nearby. Scoping the scan the way DMZ scopes credit is what makes the two agree.
     *
     * <p>Within one party this still collapses to exactly one set, which is the behaviour it was written for:
     * every member resolves the same party, so a relog, a resummon or a second member crossing the gate all see
     * the existing set and never stack another.
     *
     * <p>A mob whose ownership cannot be determined ({@code creditsPartyOf} returning null) is NOT counted. That
     * keeps the failure direction "spawn anyway": an extra set is a nuisance, no set at all is a dead end.
     */
    private static boolean liveQuestMobsAlive(ServerPlayer player, String questId) {
        return liveQuestMobsAlive(player, questId, LIVE_SCAN_RADIUS);
    }

    /** As {@link #liveQuestMobsAlive(ServerPlayer, String)}, with an explicit scan radius. */
    private static boolean liveQuestMobsAlive(ServerPlayer player, String questId, double radius) {
        try {
            ServerLevel level = player.serverLevel();
            AABB box = player.getBoundingBox().inflate(radius);
            List<LivingEntity> hits = level.getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != player && e.isAlive()
                            && questId.equals(e.getPersistentData().getString(DMZ_QUEST_KEY_TAG))
                            && Boolean.TRUE.equals(QuestMobOwnership.creditsPartyOf(e, player)));
            return !hits.isEmpty();
        } catch (Throwable t) {
            // On any failure, report "none alive" so we fall through to current spawn behavior rather than crash.
            return false;
        }
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
            DmzNpc.LOGGER.debug("[{}] deferred-spawn tick failed: {}", DmzNpc.MODID, t.toString());
        }
        try {
            healOwed(player);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] quest kill-objective heal failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    /**
     * Spawns a replacement kill mob that is OWED, once, and nothing else.
     *
     * <p>DMZ removes a quest mob on purpose when its owner dies, logs out, changes dimension or goes out of range,
     * and gives the player the quest screen's resummon button to bring it back when they are ready. An earlier
     * version of this heal treated every such removal (and every ledger record left behind on another shard) as a
     * lost mob and spawned a new one every two minutes wherever the player was, so a boss the player kept losing
     * to hunted them across dimensions and shards. Now a replacement is owed in exactly two cases:
     * <ul>
     *   <li>the mob was KILLED but the kill left its objective short, typically because the objective was still
     *       locked ({@link #onQuestMobLeave}); it is owed in the dimension it died in, and</li>
     *   <li>a sequence-gated mob was refused because its objective was still locked ({@link #onQuestMobJoin}).</li>
     * </ul>
     * An owed spawn waits until the objective is unlocked and the player is alive. If the player is in a different
     * dimension than the one it was owed in, the debt is dropped: that is a player who moved on, and resummon is theirs.
     */
    private static void healOwed(ServerPlayer player) {
        CompoundTag pers = persisted(player);
        if (!pers.contains(OWED_TAG)) {
            return;
        }
        CompoundTag owed = pers.getCompound(OWED_TAG);
        if (owed.isEmpty()) {
            pers.remove(OWED_TAG);
            return;
        }
        if (player.isDeadOrDying()) {
            return;
        }
        StatsData stats = DmzForms.stats(player);
        PlayerQuestData qd = stats == null ? null : stats.getPlayerQuestData();
        if (qd == null) {
            return;
        }
        String here = player.level().dimension().location().toString();
        for (String questKey : new ArrayList<>(owed.getAllKeys())) {
            String dim = owed.getString(questKey);
            if (!qd.isQuestAccepted(questKey) || qd.isQuestCompleted(questKey) || (!dim.isEmpty() && !dim.equals(here))) {
                owed.remove(questKey);
                continue;
            }
            Quest quest = QuestRegistry.getQuest(questKey);
            if (quest == null || quest.getObjectives() == null) {
                owed.remove(questKey);
                continue;
            }
            if (!hasActiveIncompleteQuestKill(qd, questKey, quest)) {
                continue; // nothing is its turn yet (or everything is done, which the next pass clears)
            }
            DeferredSpawnRegistry.Gate gate = DeferredSpawnRegistry.gateFor(questKey);
            if (gate != null && !gate.reached(player)) {
                continue; // the location deferral owns this spawn
            }
            owed.remove(questKey);
            if (adoptUntracked(player, questKey)) {
                continue; // one of this party's mobs is still around
            }
            String limitKey = player.getUUID() + "|" + questKey;
            long now = System.currentTimeMillis();
            Long last = HEAL_LAST_SPAWN.get(limitKey);
            if (last != null && now - last < HEAL_MIN_INTERVAL_MS) {
                continue; // a kill that never credits must not turn into a respawn loop
            }
            HEAL_LAST_SPAWN.put(limitKey, now);
            StringBuilder progress = new StringBuilder();
            List<QuestObjective> objectives = quest.getObjectives();
            for (int i = 0; i < objectives.size(); i++) {
                if (objectives.get(i) instanceof KillObjective) {
                    progress.append(" obj").append(i).append('=').append(partyProgress(player, qd, questKey, i))
                            .append('/').append(quest.getObjectiveRequired(qd, questKey, i));
                }
            }
            DmzNpc.LOGGER.info("[{}] Spawning owed quest kill mob(s) for {} on {}:{}", DmzNpc.MODID,
                    player.getGameProfile().getName(), questKey, progress);
            spawnNow(player, questKey, PartyManager.getAllPartyMembers(player).size(), qd.getQuestDifficulty(questKey), false);
        }
        if (owed.isEmpty()) {
            pers.remove(OWED_TAG);
        } else {
            pers.put(OWED_TAG, owed);
        }
    }

    private static void markOwed(ServerPlayer owner, String questKey, String dimension) {
        CompoundTag pers = persisted(owner);
        CompoundTag owed = pers.getCompound(OWED_TAG);
        owed.putString(questKey, dimension == null ? "" : dimension);
        pers.put(OWED_TAG, owed);
    }

    /** True when this objective is not yet the owner's turn under DMZ's sequencing. Unknown state reads as unlocked. */
    private static boolean objectiveLocked(ServerPlayer owner, String questKey, int index) {
        StatsData stats = DmzForms.stats(owner);
        PlayerQuestData qd = stats == null ? null : stats.getPlayerQuestData();
        Quest quest = QuestRegistry.getQuest(questKey);
        List<QuestObjective> objectives = quest == null ? null : quest.getObjectives();
        if (qd == null || objectives == null || index < 0 || index >= objectives.size()) {
            return false;
        }
        return !killObjectiveUnlocked(objectives, qd, questKey, quest, index);
    }

    /** How many more of this objective the owner's party still needs, by the best progress any member holds. */
    private static int remainingForParty(ServerPlayer owner, String questKey, int index) {
        try {
            StatsData stats = DmzForms.stats(owner);
            PlayerQuestData qd = stats == null ? null : stats.getPlayerQuestData();
            Quest quest = QuestRegistry.getQuest(questKey);
            if (qd == null || quest == null || !qd.isQuestAccepted(questKey) || qd.isQuestCompleted(questKey)) {
                return 0;
            }
            return Math.max(0, quest.getObjectiveRequired(qd, questKey, index) - partyProgress(owner, qd, questKey, index));
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The highest progress any online party member has on this objective, the controller included.
     *
     * <p>DMZ credits a kill to the killer's own quest data, and its party sync only copies the LEADER's state onto
     * members, never a member's progress back. So when a member lands the kill the controller's own copy can stay at
     * zero, and reading only the controller made the heal replace a mob the party had already killed.
     */
    private static int partyProgress(ServerPlayer controller, PlayerQuestData controllerQd, String questKey, int index) {
        int best = controllerQd.getObjectiveProgress(questKey, index);
        try {
            for (ServerPlayer member : PartyManager.getAllPartyMembers(controller)) {
                if (member == null || member == controller) {
                    continue;
                }
                StatsData stats = DmzForms.stats(member);
                PlayerQuestData mqd = stats == null ? null : stats.getPlayerQuestData();
                if (mqd != null) {
                    best = Math.max(best, mqd.getObjectiveProgress(questKey, index));
                }
            }
        } catch (Throwable ignored) {
            // the controller's own progress stands
        }
        return best;
    }

    /** Record any live, untracked mob this party already has for the quest. True if one was found. */
    private static boolean adoptUntracked(ServerPlayer player, String questKey) {
        try {
            AABB box = player.getBoundingBox().inflate(DUP_GUARD_RADIUS);
            List<LivingEntity> hits = player.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != player && e.isAlive()
                            && e.getPersistentData().contains(DMZ_QUEST_OBJECTIVE_INDEX_TAG)
                            && questKey.equals(e.getPersistentData().getString(DMZ_QUEST_KEY_TAG))
                            && Boolean.TRUE.equals(QuestMobOwnership.creditsPartyOf(e, player)));
            for (LivingEntity e : hits) {
                ledgerAdd(player, ledgerKey(questKey, e.getPersistentData().getInt(DMZ_QUEST_OBJECTIVE_INDEX_TAG)),
                        e.getUUID());
            }
            return !hits.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** How many more of this objective the owner still needs, at least 1 so a lookup failure never refuses a spawn. */
    private static int remainingFor(ServerPlayer owner, String questKey, int index) {
        try {
            StatsData stats = DmzForms.stats(owner);
            PlayerQuestData qd = stats == null ? null : stats.getPlayerQuestData();
            Quest quest = QuestRegistry.getQuest(questKey);
            if (qd == null || quest == null) {
                return Integer.MAX_VALUE;
            }
            return Math.max(1, quest.getObjectiveRequired(qd, questKey, index) - qd.getObjectiveProgress(questKey, index));
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    /** The recorded mob that is mid-transformation, if any: the one a freshly joining quest mob is replacing. */
    private static UUID transformingPredecessor(MinecraftServer server, List<UUID> recorded) {
        for (UUID id : recorded) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity e = level.getEntity(id);
                if (e != null) {
                    if (e.isAlive() && e instanceof DBSagasEntity saga && saga.isTransforming()) {
                        return id;
                    }
                    break;
                }
            }
        }
        return null;
    }

    /** How many recorded mobs are alive in any loaded level. UUID lookups only. */
    private static int aliveCount(MinecraftServer server, List<UUID> recorded) {
        int alive = 0;
        for (UUID id : recorded) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity e = level.getEntity(id);
                if (e != null) {
                    if (e.isAlive()) {
                        alive++;
                    }
                    break;
                }
            }
        }
        return alive;
    }

    private static String ledgerKey(String questKey, int index) {
        return questKey + "|" + index;
    }

    private static CompoundTag persisted(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG)) {
            root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    private static List<UUID> ledgerGet(ServerPlayer owner, String key) {
        List<UUID> out = new ArrayList<>();
        CompoundTag ledger = persisted(owner).getCompound(LEDGER_TAG);
        ListTag list = ledger.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                out.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // a malformed entry is simply not a mob
            }
        }
        return out;
    }

    private static void ledgerAdd(ServerPlayer owner, String key, UUID id) {
        CompoundTag pers = persisted(owner);
        CompoundTag ledger = pers.getCompound(LEDGER_TAG);
        ListTag list = ledger.getList(key, Tag.TAG_STRING);
        list.add(StringTag.valueOf(id.toString()));
        ledger.put(key, list);
        pers.put(LEDGER_TAG, ledger);
    }

    private static void ledgerRemove(ServerPlayer owner, String key, UUID id) {
        CompoundTag pers = persisted(owner);
        CompoundTag ledger = pers.getCompound(LEDGER_TAG);
        if (!ledger.contains(key)) {
            return;
        }
        ListTag list = ledger.getList(key, Tag.TAG_STRING);
        String target = id.toString();
        list.removeIf(t -> target.equals(t.getAsString()));
        if (list.isEmpty()) {
            ledger.remove(key);
        } else {
            ledger.put(key, list);
        }
        pers.put(LEDGER_TAG, ledger);
    }

    private static void ledgerClear(ServerPlayer owner, String key) {
        CompoundTag pers = persisted(owner);
        CompoundTag ledger = pers.getCompound(LEDGER_TAG);
        ledger.remove(key);
        pers.put(LEDGER_TAG, ledger);
    }

    /**
     * True when the quest has a QUEST-spawned KILL objective that is unlocked (its turn, by DMZ's sequencing)
     * and still short of its requirement. Mirrors {@code QuestEvents.isKillObjectiveUnlocked} exactly so we only
     * (re)spawn a mob for an objective the server would actually credit right now.
     */
    private static boolean hasActiveIncompleteQuestKill(PlayerQuestData qd, String questKey, Quest quest) {
        List<QuestObjective> objectives = quest.getObjectives();
        if (objectives == null) {
            return false;
        }
        for (int i = 0; i < objectives.size(); i++) {
            if (!(objectives.get(i) instanceof KillObjective killObjective)) {
                continue;
            }
            if (killObjective.getSpawnMode() != KillObjective.SpawnMode.QUEST) {
                continue; // a NATURAL-spawn kill is not placed by DMZ, so there is nothing for us to replace
            }
            if (qd.getObjectiveProgress(questKey, i) >= quest.getObjectiveRequired(qd, questKey, i)) {
                continue; // already done
            }
            if (killObjectiveUnlocked(objectives, qd, questKey, quest, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code QuestEvents.isKillObjectiveUnlocked}, reimplemented: a parallel quest unlocks every objective;
     * otherwise a KILL objective unlocks once every objective before its contiguous KILL block is complete.
     */
    private static boolean killObjectiveUnlocked(List<QuestObjective> objectives, PlayerQuestData qd,
                                                 String questKey, Quest quest, int index) {
        if (quest.isParallelObjectives()) {
            return true;
        }
        int blockStart = index;
        while (blockStart > 0 && objectives.get(blockStart - 1) instanceof KillObjective) {
            blockStart--;
        }
        for (int i = 0; i < blockStart; i++) {
            if (qd.getObjectiveProgress(questKey, i) < quest.getObjectiveRequired(qd, questKey, i)) {
                return false;
            }
        }
        return true;
    }

    private static void tick(ServerPlayer player) {
        // The deferral lives on the party controller. Whichever member is ticking, read the controller's store
        // so a synced-accepted quest is replayed once for the whole party by whoever first crosses the gate.
        ServerPlayer controller = resolveController(player);
        if (!controller.getPersistentData().contains(TAG)) {
            return;
        }
        CompoundTag store = controller.getPersistentData().getCompound(TAG);
        if (store.isEmpty()) {
            controller.getPersistentData().remove(TAG);
            return;
        }
        // Active-state is per-player quest data; use the ticking member's own to decide if the quest still stands.
        StatsData stats = DmzForms.stats(player);
        PlayerQuestData qd = stats == null ? null : stats.getPlayerQuestData();
        Set<String> accepted = qd == null ? null : qd.getAcceptedQuestIds();

        List<String> done = new ArrayList<>();
        for (String questId : new ArrayList<>(store.getAllKeys())) {
            DeferredSpawnRegistry.Gate gate = DeferredSpawnRegistry.gateFor(questId);
            boolean stillActive = accepted != null && accepted.contains(questId)
                    && (qd == null || !qd.isQuestCompleted(questId));
            if (gate == null || !stillActive) {
                done.add(questId); // no longer gated / dropped / completed: forget it
                continue;
            }
            // Gate is checked against the member physically present, spawn happens at that member.
            if (gate.reached(player)) {
                CompoundTag entry = store.getCompound(questId);
                spawnNow(player, questId, entry.getInt("count"), parseDiff(entry.getString("diff")));
                done.add(questId);
            }
        }
        if (!done.isEmpty()) {
            for (String k : done) {
                store.remove(k);
            }
            if (store.isEmpty()) {
                controller.getPersistentData().remove(TAG);
            } else {
                controller.getPersistentData().put(TAG, store);
            }
        }
    }

    /**
     * Replay DMZ's own spawn with the captured args, bypassing our deferral so it actually spawns. Idempotent:
     * if a set for this quest is already alive near the player (relog re-defer, resummon, or another member who
     * already crossed the gate), we skip so the live count never exceeds the one intended set.
     */
    private static void spawnNow(ServerPlayer player, String questId, int count, Difficulty difficulty) {
        spawnNow(player, questId, count, difficulty, true);
    }

    /**
     * As above. The heal passes {@code checkLive = false}: it has already decided the objective is empty from the
     * ledger, and the area scan would see the OTHER objectives' live mobs and refuse. Duplicates of those are
     * refused one by one at join instead (see {@link #onQuestMobJoin}).
     */
    private static void spawnNow(ServerPlayer player, String questId, int count, Difficulty difficulty, boolean checkLive) {
        if (checkLive && liveQuestMobsAlive(player, questId)) {
            DmzNpc.LOGGER.debug("[{}] skipped duplicate deferred spawn for {}: set already alive", DmzNpc.MODID, questId);
            return;
        }
        bypass = true;
        try {
            QuestService.spawnKillObjectivesForQuest(player, questId, count, difficulty);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] deferred spawn replay failed for {}: {}", DmzNpc.MODID, questId, t.toString());
        } finally {
            bypass = false;
        }
    }

    private static void clearPending(ServerPlayer player, String questId) {
        if (!player.getPersistentData().contains(TAG)) {
            return;
        }
        CompoundTag store = player.getPersistentData().getCompound(TAG);
        if (store.contains(questId)) {
            store.remove(questId);
            if (store.isEmpty()) {
                player.getPersistentData().remove(TAG);
            } else {
                player.getPersistentData().put(TAG, store);
            }
        }
    }

    private static Difficulty parseDiff(String s) {
        if (s == null || s.isBlank()) {
            return Difficulty.NORMAL;
        }
        try {
            return Difficulty.valueOf(s);
        } catch (Exception e) {
            return Difficulty.NORMAL;
        }
    }
}
