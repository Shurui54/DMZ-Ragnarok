package net.shurui.dev.sdu.event;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.quest.QuestRepeatStore;
import net.shurui.dev.sdu.quest.RepeatConfig;

import com.dragonminez.common.quest.Difficulty;
import com.dragonminez.common.quest.PartyManager;
import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestReward;
import com.dragonminez.common.quest.QuestService;
import com.dragonminez.common.stats.StatsData;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime for addon repeatable-on-a-delay quests (intervals in {@link RepeatConfig}). DMZ has no native
 * "complete, then re-unlock after a cooldown": it tracks per-quest SUCCESS and stores no completion
 * timestamp, so we build the whole cycle addon-side.
 *
 * <p>Once a second per online player: (1) detect quests now FINISHED (see below) and, if they have a repeat
 * interval, stamp the finish time in {@link QuestRepeatStore} (persistent SavedData, so daily/weekly cooldowns
 * survive relog/restart). (2) once {@code now - lastCompleted >= interval*1000}, {@code resetQuest} +
 * {@code syncQuestState} to re-unlock, and record the reset so the next finish starts a fresh cooldown.
 *
 * <h2>FINISHED means SUCCESS and every unlocked reward claimed, not merely SUCCESS</h2>
 *
 * <p>DMZ's {@code QuestStatus} has no "turned in" vs "reward claimed" state: reward-claim lives on a parallel
 * axis, a {@code rewardsClaimed} map on each {@code QuestProgress}. For a quest with NO turn-in NPC,
 * {@code QuestEvents.checkAndComplete} sets SUCCESS the instant objectives are met and grants NOTHING; the
 * reward is claimed later from the quest tree or an NPC. So such a quest is SUCCESS with rewards still unclaimed
 * for as long as the player takes. {@code PlayerQuestData.resetQuest} does {@code quests.remove(questId)}, which
 * drops the whole {@code QuestProgress} and with it the unclaimed rewards: resetting a SUCCESS-but-unclaimed
 * quest silently and permanently destroys the reward. So we only stamp or reset a quest once it is SUCCESS AND
 * every difficulty-unlocked reward has been claimed ({@link #isFinished}, which reimplements DMZ's private
 * {@code QuestService.hasUnclaimedRewards}). If the quest or its rewards cannot be resolved we report NOT
 * finished and leave it alone: a missed repeat is an inconvenience, a deleted reward is unrecoverable. For
 * quests that DO have a turn-in NPC this changes nothing: {@code QuestService.turnInQuest} completes and claims
 * in one interaction, so the gate is satisfied the same tick. The gate only bites the no-turn-in auto-complete
 * quests, which is exactly where the reward loss lived.
 *
 * <h2>A NEW finish is an edge, not "I hold a live stamp"</h2>
 *
 * <p>Stamping used to fire whenever the store held no LIVE stamp, and treat a live stamp as proof that the
 * current finished state was the one it referred to. It is not. A live stamp can outlive the DMZ status that
 * produced it whenever the status is cleared by some path other than our own reset (a party re-sync bouncing a
 * member, a manual grant, a shard hop), which leaves the stamp live for ever, so every future finish is judged
 * against an ancient time and reset about a second after the player earns it. Live data showed stamps up to 119
 * hours old doing exactly that. So we edge-detect instead: {@link #FINISHED_LAST_SWEEP} remembers the quests
 * observed finished on the PREVIOUS sweep per player. A quest ALREADY observed finished only has an elapsed live
 * stamp trigger a reset, nothing else.
 *
 * <p>The first sighting of a finished quest this session is NOT simply "a new finish". The in-memory set is
 * empty after every relog, every shard hop (a {@code /server} hop is a real backend disconnect, so it logs out
 * on the old shard and arrives fresh on the new one), and every twice-daily scheduled halt, so on this network
 * "no previous observation" is the common case. The persistent store, unlike the set, syncs across shards, so
 * its stamp usually survives what wipes the set. We therefore read the stamp rather than blindly re-stamping:
 * no live stamp is a genuine new finish (stamp now). On the player's FIRST sweep after arriving, a live stamp is
 * trusted whatever its age, because the finish happened while they were online and was stamped where it happened;
 * an elapsed one is a cooldown that ran out while they were offline and resets once {@code ARRIVAL_GRACE_MS} has
 * let the store catch up. Mid-session, a quest that has just become finished against a stamp younger than the
 * interval keeps it, and against one at least as old is a stale stamp, so we re-stamp {@code now} rather than
 * reset, which denies it a free early repeat. (Re-stamping on arrival too is what used to stop cooldowns running
 * down offline: anyone away longer than the interval came back to a full new one.) The claim gate is what
 * lets us treat this first-sighting call as a fairness question at all: with it, a reset can only fire on a
 * quest whose rewards are all claimed, so no first-sighting branch can destroy a reward.
 *
 * <p>"Live" in {@link QuestRepeatStore} still does real work and stays: it is the cross-shard half of the fix.
 * The reset guard used to be mere PRESENCE of a stamp, with the reset DELETING it, and a deletion cannot
 * survive {@code QuestRepeatStore.mergeInto}: a peer shard reinstated the deleted stamp with its original
 * ancient time, and the player's next finish was measured against that and reset about a second later. See the
 * store's class note. The edge-detection above is the in-memory half; the two-timestamp store is the persistent
 * half; neither replaces the other.
 *
 * <p>Party-sync gotcha: DMZ party-syncs quest state ({@code mergeQuestStateFrom} takes the max status), so
 * resetting one member could get re-propagated to SUCCESS from a still-done partner mid-sync. To stop the
 * bounce-back, in a party we sweep EVERY current member in the same tick. Partyless = singleton. Each member is
 * still judged on their own cooldown, because sweeping the party off one member's timer wiped completions that
 * partners had only just earned. This doesn't perfectly serialize against a concurrent DMZ merge, so a
 * live-party launch-test is the real proof.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class QuestRepeatHandler {

    // Per-player set of repeatable quest keys observed FINISHED on the previous sweep. A quest finished now but
    // absent here is a NEW finish and gets a fresh stamp; only one already present may have an elapsed live
    // stamp reset it. Keyed by UUID and cleared on logout (onLogout), so it holds at most one small set per
    // ONLINE player and cannot grow for every account that ever joined. ConcurrentHashMap because, although both
    // writers (player tick and logout) are on the server thread today, nothing enforces that and the cost is
    // nil. Rebuilt wholesale each sweep rather than mutated, so a quest leaving the completed set (reset,
    // abandoned) drops out automatically and a later re-completion is correctly seen as a new finish.
    private static final Map<UUID, Set<String>> FINISHED_LAST_SWEEP = new ConcurrentHashMap<>();

    // When each online player's first sweep ran, cleared on logout alongside FINISHED_LAST_SWEEP. Resets are held
    // for ARRIVAL_GRACE_MS after it: a player who finishes a repeat and hops shards at once can land before
    // sdu:quest_repeats (published every 5 s) has carried the new stamp, and the stale previous one would read as
    // elapsed. Three sync intervals lets the newer stamp arrive and win the merge before anything is reset.
    private static final Map<UUID, Long> ARRIVED_AT = new ConcurrentHashMap<>();
    private static final long ARRIVAL_GRACE_MS = 15_000L;

    private QuestRepeatHandler() {
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // Drop the player's edge-detection state. On their next arrival the first sweep trusts the stored stamp
        // (Case 0 in tick), so a cooldown keeps running down while they are offline.
        FINISHED_LAST_SWEEP.remove(event.getEntity().getUUID());
        ARRIVED_AT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player) || player.tickCount % 20 != 0) {
            return; // once per second, matching QuestTimerHandler's cadence
        }
        // nothing configured => no work
        if (RepeatConfig.keys().isEmpty()) {
            return;
        }
        try {
            tick(player);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] quest-repeat tick failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    private static void tick(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        StatsData stats = DmzForms.stats(player);
        if (stats == null) {
            return;
        }
        PlayerQuestData qd = stats.getPlayerQuestData();
        if (qd == null) {
            return;
        }
        QuestRepeatStore store = QuestRepeatStore.get(server);
        long now = System.currentTimeMillis();
        UUID uuid = player.getUUID();

        // What was finished last sweep, and the fresh set we build this sweep. Rebuilding rather than mutating is
        // what makes a reset or an abandon drop a key: a quest that leaves the completed set is simply never
        // visited here, so it falls out of nowFinished and a later re-completion reads as a new finish.
        // No entry at all means this is the player's FIRST sweep since they arrived (login, shard hop, restart). The
        // entry is written on every completed sweep, even when empty, so its absence is exactly "arrived".
        boolean firstSweep = !FINISHED_LAST_SWEEP.containsKey(uuid);
        if (firstSweep) {
            ARRIVED_AT.put(uuid, now);
        }
        boolean settled = now - ARRIVED_AT.getOrDefault(uuid, 0L) >= ARRIVAL_GRACE_MS;
        Set<String> prevFinished = FINISHED_LAST_SWEEP.getOrDefault(uuid, Set.of());
        Set<String> nowFinished = new HashSet<>();

        Set<String> completed = qd.getCompletedQuestIds();
        if (completed != null) {
            for (String key : completed) {
                long intervalSeconds = RepeatConfig.get(key);
                if (intervalSeconds <= 0) {
                    continue; // not a repeatable quest
                }
                // SUCCESS alone is not enough: a no-turn-in quest is SUCCESS with its reward still unclaimed, and
                // resetting it there deletes the reward. Only treat it as finished, hence stampable and
                // resettable, once every unlocked reward is claimed. Unresolvable => NOT finished => left alone.
                if (!isFinished(qd, key)) {
                    continue;
                }
                // Add to nowFinished in EVERY branch below, so the next sweep sees this as already-observed and
                // can reset it normally once the interval passes.
                nowFinished.add(key);
                if (prevFinished.contains(key)) {
                    // Already observed finished on a previous sweep this session: the behaviour the edge-detection
                    // was built for. An elapsed live stamp re-unlocks, nothing else.
                    if (settled && cooldownElapsed(store, uuid, key, intervalSeconds, now)) {
                        resetForPartyOrSelf(player, key, store, now, intervalSeconds);
                    }
                    continue;
                }
                // First sighting of this finished quest THIS session: we have no in-memory proof of whether a
                // stamp already present belongs to it. FINISHED_LAST_SWEEP is in-memory and cleared on logout,
                // and on this network "no previous observation" is the common case, not the rare one: players
                // hop shards with /server (a real backend disconnect, so it logs out on the old shard and arrives
                // fresh on the new one) and all shards halt twice a day, wiping the map for everyone. The store,
                // by contrast, syncs across shards through sdu:quest_repeats, so its stamp usually DID carry over
                // even when our set did not. Treating every missing observation as a new finish therefore reset
                // every cooldown on every hop or restart, which for 1800 s and 3600 s intervals means an ordinary
                // session never reaches a repeat. So distinguish the stamp's state rather than blindly re-stamp.
                //
                // The claim gate above (isFinished) is what makes this safe to get wrong: a reset can now only
                // ever fire on a quest whose rewards are all claimed, so no branch here can destroy a reward. This
                // is now a fairness guard (do not hand out a free early repeat) and no longer a data-loss guard.
                if (!store.hasLiveCompletion(uuid, key)) {
                    // Case 1: no live stamp. A genuine new finish. Stamp now.
                    store.recordCompletion(uuid, key, now);
                } else if (firstSweep) {
                    // Case 0: the player has just ARRIVED holding a finished quest and a live stamp. Trust the stamp
                    // whatever its age. A finish happens while online, so the shard they were on watched it happen
                    // and stamped it as an edge on the spot; the vault payload is applied at login HIGHEST, before
                    // this first sweep, so what we see here is that same finished state carried over, not a new one.
                    // An elapsed stamp therefore means the cooldown ran out while they were away, and a sweep once
                    // ARRIVAL_GRACE_MS has passed (the key is in nowFinished) resets it. Re-stamping here instead, as Case 3 does, restarted
                    // the whole interval for anyone offline longer than it, so cooldowns never ran down offline.
                } else if (now - store.lastCompleted(uuid, key) < intervalSeconds * 1000L) {
                    // Case 2: a live stamp younger than the interval. A cooldown legitimately still running that
                    // we are simply seeing for the first time this session (a relog, a shard hop, a scheduled
                    // restart). TRUST it: do not re-stamp and do not reset. This is the branch that removes the
                    // per-hop and twice-daily cooldown tax.
                } else {
                    // Case 3: mid-session (Case 0 took arrivals), the quest has just BECOME finished while a live
                    // stamp at least as old as the interval is still held. That is a fresh finish being judged
                    // against a stale stamp of the kind that caused the original early-reset bug (a stamp outliving
                    // the status that made it), not a cooldown that ran out offline. Re-stamp with now
                    // rather than resetting, so a stale stamp cannot hand out a free early repeat. This SELF-HEALS:
                    // after the re-stamp the stamp is fresh, so this branch fires at most once per player and
                    // quest, costing one extra interval of waiting once. Logged so the stale ones stay visible.
                    DmzNpc.LOGGER.debug("[{}] quest-repeat re-stamping {} for {}: live stamp age {} ms >= interval {} ms",
                            DmzNpc.MODID, key, uuid, now - store.lastCompleted(uuid, key), intervalSeconds * 1000L);
                    store.recordCompletion(uuid, key, now);
                }
            }
        }
        // Always replace the remembered set, even when empty, so keys that stopped being finished are reconciled
        // away. Transient bail-outs above (no server/stats/qd) leave the old set untouched, which is harmless.
        FINISHED_LAST_SWEEP.put(uuid, nowFinished);
    }

    /**
     * Whether a quest is finished for this player: SUCCESS with every difficulty-unlocked reward claimed AND, when
     * the quest carries any rewards, at least one of them actually claimed. Reimplements DMZ's private
     * {@code QuestService.hasUnclaimedRewards} inverted, then hardened. The quests we pass in come from
     * {@code getCompletedQuestIds()} so are already SUCCESS; the work here is the reward-claim half.
     *
     * <p>If the quest cannot be resolved, carries no resolvable rewards, or evaluating a reward throws, we
     * CANNOT prove every reward is claimed, so we report NOT finished. That is the safe direction on purpose:
     * finished gates the reset, and resetting a quest whose reward is in fact still unclaimed destroys it.
     *
     * <p>The "at least one reward claimed" clause is the cross-shard hardening: a party sync on arrival can flip a
     * member's difficulty (see the comment on the check below), which would otherwise make an unclaimed-but-owed
     * reward read as locked and let the reset drop it. Requiring an actual claim before a rewarded quest counts as
     * finished closes that, at the only cost of never auto-repeating a quest a player was never paid for, which loses
     * them nothing.
     */
    private static boolean isFinished(PlayerQuestData qd, String key) {
        QuestService.ResolvedQuest resolved;
        try {
            resolved = QuestService.resolveQuest(key);
        } catch (Throwable t) {
            return false;
        }
        if (resolved == null) {
            return false;
        }
        Quest quest = resolved.quest();
        if (quest == null) {
            return false;
        }
        List<QuestReward> rewards;
        try {
            rewards = quest.getRewards();
        } catch (Throwable t) {
            return false;
        }
        if (rewards == null) {
            return false;
        }
        try {
            Difficulty difficulty = qd.getDifficulty();
            boolean anyReward = false;
            boolean anyClaimed = false;
            for (int i = 0; i < rewards.size(); i++) {
                QuestReward reward = rewards.get(i);
                if (reward == null) {
                    continue;
                }
                anyReward = true;
                if (qd.isRewardClaimed(key, i)) {
                    anyClaimed = true;
                    continue;
                }
                // An unlocked reward that has not been claimed means the quest is not finished. Locked-for-this-
                // difficulty rewards are never owed UNDER THIS DIFFICULTY, so on their own they do not block.
                if (reward.isUnlockedFor(difficulty)) {
                    return false;
                }
            }
            // A quest that carries rewards but from which the player has claimed NOTHING is NOT finished, even when
            // every reward reads as locked for the difficulty currently on the PlayerQuestData. That verdict cannot
            // be trusted across a shard hop: DMZ's mergeQuestStateFrom copies the party source's difficulty onto the
            // receiver (PlayerQuestData sets this.difficulty = other.difficulty on every merge), and DmzPartyArrivalImpl
            // fires exactly that sync on arrival, so a member whose difficulty was flipped by a party sync on a server
            // swap can have a reward they legitimately earned on THEIR difficulty read here as "locked, not owed". The
            // old gate then reported finished with an empty claim map, and the reset dropped the QuestProgress and the
            // unclaimed reward with it: "the quest reset when not claimed after swapping servers". Treat "completed but
            // paid nothing" as not finished and leave it alone. A repeatable that never paid out loses nothing by not
            // repeating, whereas an unclaimed reward is unrecoverable once resetQuest removes the QuestProgress. A quest
            // with no rewards at all (anyReward false) still reports finished, since there is nothing to lose.
            if (anyReward && !anyClaimed) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    /** A player's own cooldown for a quest is up: they hold a live stamp and the interval has passed since it. */
    private static boolean cooldownElapsed(QuestRepeatStore store, UUID player, String key,
                                           long intervalSeconds, long now) {
        if (!store.hasLiveCompletion(player, key)) {
            return false;
        }
        return now - store.lastCompleted(player, key) >= intervalSeconds * 1000L;
    }

    // Reset (re-unlock) a repeatable quest. Solo this is just the player. In a party it is gated on the LEADER
    // and resets the whole eligible group in one pass, then syncs once, because of how DMZ propagates state.
    //
    // DMZ's party quest sync is leader-directional and max-status. QuestService.syncQuestState(anyMember), when
    // in a party, calls PartyManager.syncPartyQuestState, which resolves the LEADER (resolveQuestController) and
    // for every other member does member.mergeQuestStateFrom(leader). mergeForwardFrom keeps the higher status
    // by rank (NOT_STARTED=0, FAILED=1, ACCEPTED=2, SUCCESS=3) and copies status, difficulty and objectives, but
    // it does NOT copy rewardsClaimed. So if we reset a member while the leader still holds SUCCESS, the next
    // sync pushes SUCCESS straight back onto that member with an EMPTY claim map: a reward they already collected
    // becomes claimable again (up to 2.5M TPS per bounce). Resetting the leader IN THE SAME PASS is what removes
    // the bounce, not any ordering trick: once the leader is NOT_STARTED it has nothing higher to propagate.
    //
    // Hence: while the LEADER still holds this quest at SUCCESS, reset nobody unless the leader is finished and the
    // leader's OWN cooldown has elapsed. A member whose timer elapsed first simply keeps the completion until the
    // leader's does too. These quests are party_scaling and get done together, so the cooldowns are
    // near-synchronised in practice, and the only alternative (resetting the member while the leader is at SUCCESS)
    // manufactures a duplicate reward.
    //
    // The exception is when the leader is NOT at SUCCESS (they already had it reset, or never completed it). Then
    // no leader->member sync can bounce a SUCCESS back onto anyone, so there is no hazard: the member is reset on
    // their own. This is what frees a member who was offline at the tick the leader's cooldown elapsed, and so
    // missed the single group-reset pass; otherwise they would wait on the leader for ever, because the leader
    // never again holds a live elapsed completion for that cycle.
    //
    // Each member is still judged on THEIR OWN cooldown and reward-claim state for WHETHER they are reset in this
    // pass; the leader gate is an extra precondition on the pass as a whole, not a replacement. A partner whose
    // interval has not passed keeps their completion: after the single leader sync the merge leaves them at their
    // own SUCCESS (higher than the leader's reset NOT_STARTED) with their own rewardsClaimed untouched.
    private static void resetForPartyOrSelf(ServerPlayer player, String key, QuestRepeatStore store, long now,
                                            long intervalSeconds) {
        boolean inParty;
        try {
            inParty = PartyManager.isInParty(player);
        } catch (Throwable t) {
            inParty = false;
        }

        // Solo path, unchanged: reset the player and sync the player.
        if (!inParty) {
            resetMember(player, key, store, now, intervalSeconds);
            try {
                QuestService.syncQuestState(player);
            } catch (Throwable t) {
                DmzNpc.LOGGER.debug("[{}] quest-repeat solo sync failed: {}", DmzNpc.MODID, t.toString());
            }
            return;
        }

        // Identify the leader exactly as DMZ's own merge does, so the entity we gate on, reset and sync is the
        // one syncPartyQuestState will treat as the source. resolveQuestController returns the player itself when
        // it is the leader, else getPartyLeader.
        ServerPlayer leader;
        try {
            leader = PartyManager.resolveQuestController(player);
        } catch (Throwable t) {
            leader = null;
        }
        if (leader == null) {
            // Cannot identify the leader: reset no one, because resetting a member with the leader unknown risks
            // exactly the bounce. A missed repeat this tick is harmless; it retries next second.
            DmzNpc.LOGGER.debug("[{}] quest-repeat party reset for {} skipped: no leader resolved", DmzNpc.MODID, key);
            return;
        }

        StatsData leaderStats = DmzForms.stats(leader);
        PlayerQuestData leaderQd = leaderStats == null ? null : leaderStats.getPlayerQuestData();

        // The bounce this whole gate exists to prevent (a leader->member sync re-pushing SUCCESS with an empty
        // claim map) can happen ONLY while the leader itself still holds THIS quest at SUCCESS: DMZ's
        // mergeQuestStateFrom merges only quests the leader still has (resetQuest removes the entry), and
        // mergeForwardFrom only ever RAISES a member's status, never copying rewardsClaimed. So when the leader is
        // known NOT to be at SUCCESS (they already had it reset, or never completed it), there is no party hazard
        // and no reason to wait: reset just this finished, cooldown-elapsed member on their own. This rescues a
        // member who was OFFLINE at the tick the leader's cooldown elapsed and so missed the single group-reset
        // pass; without it they wait on the leader for ever, because the leader will never again hold a live
        // elapsed completion for this same cycle. resetMember still gates on the member's OWN completed, finished
        // (claim gate) and cooldown, so this cannot drop an unclaimed reward or reset a member early.
        if (leaderQd != null && !leaderQd.isQuestCompleted(key)) {
            resetMember(player, key, store, now, intervalSeconds);
            try {
                QuestService.syncQuestState(player);
            } catch (Throwable t) {
                DmzNpc.LOGGER.debug("[{}] quest-repeat member sync failed: {}", DmzNpc.MODID, t.toString());
            }
            return;
        }

        // Otherwise the leader is at SUCCESS, or its state could not be read: gate the whole pass on the leader.
        // If the leader is not finished, or the leader's own cooldown has not elapsed, reset no one: a reset with
        // the leader still at SUCCESS is the bounce.
        if (leaderQd == null
                || !isFinished(leaderQd, key)
                || !cooldownElapsed(store, leader.getUUID(), key, intervalSeconds, now)) {
            DmzNpc.LOGGER.debug("[{}] quest-repeat party reset for {} waiting on leader {}",
                    DmzNpc.MODID, key, leader.getUUID());
            return;
        }

        // Reset the leader and every eligible member FIRST, no sync in the loop. Syncing per member inside the
        // loop fires a full leader->member party merge partway through a half-reset party, which is its own bounce
        // window. getAllPartyMembers includes the leader (syncPartyQuestState skips it by UUID), so the leader is
        // reset here by passing its own per-member gates, which the pass precondition above guarantees it does.
        List<ServerPlayer> members;
        try {
            members = PartyManager.getAllPartyMembers(player);
        } catch (Throwable t) {
            members = null;
        }
        if (members == null || members.isEmpty()) {
            members = List.of(leader);
        }
        boolean leaderSeen = false;
        for (ServerPlayer member : members) {
            if (member == null) {
                continue;
            }
            if (member.getUUID().equals(leader.getUUID())) {
                leaderSeen = true;
            }
            resetMember(member, key, store, now, intervalSeconds);
        }
        // Defensive: if the party list somehow omitted the leader, reset it anyway. A leader left at SUCCESS is
        // precisely what the subsequent sync would bounce back onto everyone.
        if (!leaderSeen) {
            resetMember(leader, key, store, now, intervalSeconds);
        }

        // One sync, on the leader. syncPartyQuestState resolves the leader itself, merges leader->member for each
        // other member, and calls syncSelf on every member, so this single call notifies every client and, with
        // the leader now reset, propagates no stale SUCCESS.
        try {
            QuestService.syncQuestState(leader);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] quest-repeat party sync failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    // Reset one player's copy of the quest if and only if they have it completed, every unlocked reward is
    // claimed (so the reset cannot drop an unclaimed reward), and THEIR own cooldown has elapsed. Records the
    // reset and notifies the player. Does NOT sync quest state: the caller owns sync ordering, because in a party
    // a sync is a full leader->member merge and must fire once, after the whole group is reset.
    private static void resetMember(ServerPlayer member, String key, QuestRepeatStore store, long now,
                                    long intervalSeconds) {
        try {
            StatsData mStats = DmzForms.stats(member);
            if (mStats == null) {
                return;
            }
            PlayerQuestData mqd = mStats.getPlayerQuestData();
            if (mqd == null) {
                return;
            }
            // only reset members who actually have it completed; leave an unfinished/re-accepted partner alone
            if (!mqd.isQuestCompleted(key)) {
                return;
            }
            // and only when every unlocked reward is claimed for THAT member. Reset drops the QuestProgress and
            // the rewards with it, so resetting a member whose reward is still unclaimed would delete it.
            if (!isFinished(mqd, key)) {
                return;
            }
            // and only when THEIR cooldown is up. A member still inside their interval keeps the completion.
            if (!cooldownElapsed(store, member.getUUID(), key, intervalSeconds, now)) {
                return;
            }
            mqd.resetQuest(key);
            store.recordReset(member.getUUID(), key, now);
            member.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.npc.quest.repeatable_available"), true);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] quest-repeat reset failed for a member: {}", DmzNpc.MODID, t.toString());
        }
    }
}
