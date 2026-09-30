package net.shurui.dev.sdu.quest;

import com.dragonminez.common.quest.PartyManager;
import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestService;
import com.dragonminez.common.quest.Saga;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Party rule: a saga quest line is run at the LOWEST step any online, same-server party member is still on, until
 * everyone has caught up. DMZ's own party model routes every quest through the party LEADER as the sole controller
 * (PartyManager.resolveQuestController, QuestService.startQuest), so a mixed-progress party could not spawn saga
 * NPCs at all: if the leader was ahead of a member, starting the leader's step returned "already active" for the
 * behind member (the state is the leader's), and starting the behind step through the leader was refused the same
 * way, so QuestService.acceptQuest + spawnKillObjectives never ran. This makes the party do the behind member's
 * (lowest) step together instead: the behind member becomes the effective controller for that one start (see
 * {@code PartyControllerOverrideMixin}), so DMZ's own start path accepts and spawns it normally, and DMZ's
 * per-member kill credit (QuestEvents.creditQuestKill) handles the rest.
 *
 * <p><b>Rewards / ahead players:</b> DMZ credits each member on their OWN PlayerQuestData and skips a member who has
 * already completed the quest (QuestEvents.processAcceptedQuests filters {@code isQuestCompleted}). So a player who
 * is ahead re-fights the step with the party but gets NO duplicate progress or reward, they simply help. A behind
 * member who has the step accepted gets normal progress and their own reward on completion. This behaviour is DMZ's
 * and is left unchanged.
 *
 * <p><b>Offline / other-shard members:</b> DMZ parties are per server and
 * {@code PartyManager.getAllPartyMembers} returns only online, same-server members, so "every member" here means
 * every online same-server member. A member on another shard or offline is not counted, exactly as DMZ counts a
 * party everywhere else. Nothing cross-shard is broken.
 *
 * <p>Solo play and non-saga quests never reach the takeover path: {@link #plan} returns null for them, so DMZ runs
 * unchanged.
 */
public final class PartyLowestSaga {

    /** Party notice arg %1$s = quest title, %2$s = the behind player, when a member picks a step ahead of the party. */
    public static final String CATCHUP_KEY = "message.dmz_ragnarok.party.saga_catchup";
    /** Party notice when the last behind member has caught up and saga quests resume normally. */
    public static final String CAUGHT_UP_KEY = "message.dmz_ragnarok.party.saga_caught_up";

    /**
     * The effective controller for the single re-invoked start, read by {@code PartyControllerOverrideMixin}. Set only
     * for the duration of one synchronous {@link QuestService#startQuest(ServerPlayer, String)} on the server thread,
     * so there is no interleaving; a ThreadLocal keeps it strictly to that thread regardless.
     */
    private static final ThreadLocal<ServerPlayer> CONTROLLER_OVERRIDE = new ThreadLocal<>();

    /** Party id -&gt; whether the party was last seen catching up, so we can announce "caught up" exactly once. */
    private static final Map<UUID, Boolean> CATCHING_UP = new ConcurrentHashMap<>();

    private PartyLowestSaga() {
    }

    /** True while our own re-invocation of startQuest is in flight, so the start mixin lets it fall through to DMZ. */
    public static boolean overrideActive() {
        return CONTROLLER_OVERRIDE.get() != null;
    }

    /** The controller override for the in-flight start, or null. Read by {@code PartyControllerOverrideMixin}. */
    public static ServerPlayer controllerOverride() {
        return CONTROLLER_OVERRIDE.get();
    }

    private static void begin(ServerPlayer controller) {
        CONTROLLER_OVERRIDE.set(controller);
    }

    private static void end() {
        CONTROLLER_OVERRIDE.remove();
    }

    /** What the party should do for a requested saga start, or null when DMZ's normal path applies unchanged. */
    public record Plan(boolean takeover, boolean announceAhead, boolean allAligned,
                       ServerPlayer controller, String targetKey, Quest targetQuest,
                       UUID partyId, List<ServerPlayer> members) {
    }

    /**
     * Entry point from {@code QuestServicePartyLowestStartMixin} at the head of the public
     * {@link QuestService#startQuest(ServerPlayer, String)}. Returns true and takes over (via {@code result}) when it
     * handled the start; returns false to let DMZ run its normal path.
     */
    public static boolean handleStart(ServerPlayer requester, String questKey, java.util.function.Consumer<Component> result) {
        try {
            if (overrideActive()) {
                return false; // our own re-invocation: DMZ runs
            }
            Plan plan = plan(requester, questKey);
            if (plan == null) {
                return false; // solo, non-saga, or anything we do not steer: DMZ runs
            }
            if (!plan.takeover()) {
                // Party saga start that DMZ can run itself. If the party has now aligned after catching up, say so.
                if (plan.allAligned() && Boolean.TRUE.equals(CATCHING_UP.remove(plan.partyId()))) {
                    broadcast(plan.members(), Component.translatable(CAUGHT_UP_KEY).withStyle(ChatFormatting.GREEN));
                }
                return false;
            }
            // Steer the party onto its lowest incomplete step and let DMZ start THAT with the behind member as
            // controller, so acceptQuest + spawnKillObjectives run and the enemies spawn for the party.
            CATCHING_UP.put(plan.partyId(), Boolean.TRUE);
            if (plan.announceAhead()) {
                broadcast(plan.members(), Component.translatable(CATCHUP_KEY, questTitle(plan.targetQuest()),
                        plan.controller().getDisplayName()).withStyle(ChatFormatting.YELLOW));
            }
            begin(plan.controller());
            try {
                Component reply = QuestService.startQuest(plan.controller(), plan.targetKey());
                result.accept(reply);
            } finally {
                end();
            }
            return true;
        } catch (Throwable t) {
            // Fail open: on any error, let DMZ's own start run so a mixed party is never worse off than before.
            end();
            DmzNpc.LOGGER.debug("[{}] party lowest-saga start steering skipped: {}", DmzNpc.MODID, t.toString());
            return false;
        }
    }

    /** Compute the party's plan for a requested saga start, or null when DMZ's normal path applies. */
    private static Plan plan(ServerPlayer requester, String questKey) {
        if (requester == null || questKey == null || !PartyManager.isInParty(requester)) {
            return null;
        }
        QuestService.ResolvedQuest resolved = QuestService.resolveQuest(questKey);
        if (resolved == null) {
            return null;
        }
        Quest quest = resolved.quest();
        Saga saga = resolved.saga();
        if (quest == null || saga == null || !quest.isSagaQuest()) {
            return null; // non-saga and side/branch quests are never steered
        }
        List<Quest> line = saga.getQuests();
        if (line == null || line.isEmpty()) {
            return null;
        }
        int requestedIndex = line.indexOf(quest);
        if (requestedIndex < 0) {
            return null;
        }
        List<ServerPlayer> members = PartyManager.getAllPartyMembers(requester);
        if (members == null || members.size() <= 1) {
            return null; // effectively solo
        }
        ServerPlayer leader = PartyManager.getPartyLeader(requester);

        int[] steps = new int[members.size()];
        int targetIndex = Integer.MAX_VALUE;
        int highest = 0;
        ServerPlayer controller = null;
        for (int i = 0; i < members.size(); i++) {
            ServerPlayer m = members.get(i);
            int idx = firstIncompleteIndex(saga, line, m);
            steps[i] = idx;
            if (idx < targetIndex) {
                targetIndex = idx;
            }
            if (idx > highest) {
                highest = idx;
            }
        }
        if (targetIndex >= line.size()) {
            return null; // the whole party has finished this saga line: nothing to steer
        }
        // Prefer the requester as controller when they are the one on the lowest step, so the enemies spawn at them.
        for (ServerPlayer m : members) {
            if (firstIncompleteIndex(saga, line, m) == targetIndex) {
                if (m.getUUID().equals(requester.getUUID())) {
                    controller = m;
                    break;
                }
                if (controller == null) {
                    controller = m;
                }
            }
        }
        if (controller == null) {
            return null; // no online member actually on the lowest step (should not happen); let DMZ decide
        }
        int leaderIndex = leader == null ? targetIndex : firstIncompleteIndex(saga, line, leader);
        boolean takeover = takeoverNeeded(requestedIndex, targetIndex, leaderIndex);
        boolean announceAhead = requestedIndex > targetIndex;
        boolean allAligned = highest == targetIndex; // every member is on the same step
        String targetKey = PlayerQuestData.sagaQuestKey(saga.getId(), line.get(targetIndex).getId());
        UUID partyId = leader != null ? leader.getUUID() : requester.getUUID();
        return new Plan(takeover, announceAhead, allAligned, controller, targetKey, line.get(targetIndex),
                partyId, new ArrayList<>(members));
    }

    /**
     * The index in saga order of the first quest this player has NOT completed, or {@code line.size()} when they have
     * completed every quest in the line. This is exactly the player's current step on the ladder DMZ sequences by
     * (QuestAvailabilityChecker.isSequentiallyReachable reads the same list in the same order).
     */
    private static int firstIncompleteIndex(Saga saga, List<Quest> line, ServerPlayer player) {
        StatsData stats = DmzForms.stats(player);
        PlayerQuestData pqd = stats == null ? null : stats.getPlayerQuestData();
        if (pqd == null) {
            return 0; // unknown progress: treat as the very start, the safest "behind" answer
        }
        for (int i = 0; i < line.size(); i++) {
            String key = PlayerQuestData.sagaQuestKey(saga.getId(), line.get(i).getId());
            if (!pqd.isQuestCompleted(key)) {
                return i;
            }
        }
        return line.size();
    }

    /**
     * Pure decision, unit-tested by {@link #runSelfTest()}: take over the start when the party's lowest incomplete
     * step is not the one DMZ would actually run. We steer when the requested quest is ahead of the party's lowest
     * step, or when it is exactly the lowest step but the leader (DMZ's chosen controller) is ahead of it, since DMZ
     * would then answer "already active" for the behind member and spawn nothing. A request BELOW the lowest step is
     * a quest every member already finished, so we leave DMZ to give its normal "already active" reply.
     */
    static boolean takeoverNeeded(int requestedIndex, int targetIndex, int leaderIndex) {
        if (requestedIndex < targetIndex) {
            return false;
        }
        return requestedIndex > targetIndex || leaderIndex > targetIndex;
    }

    /** The quest's display title as a component, or a readable fallback when the title key is missing. */
    private static Component questTitle(Quest quest) {
        String title = quest == null ? null : quest.getTitle();
        if (title == null || title.isBlank()) {
            return Component.literal("the current quest");
        }
        return Component.translatable(title);
    }

    private static void broadcast(List<ServerPlayer> members, Component message) {
        if (members == null) {
            return;
        }
        for (ServerPlayer m : members) {
            if (m != null) {
                m.sendSystemMessage(message);
            }
        }
    }

    /**
     * Server-side self-test of the lowest-step decision math (no live players needed), logged once at server start so
     * a boot test exercises it. Covers: aligned parties, a member behind, a leader ahead of the lowest, and a request
     * below the lowest. Fails loud in the log but never throws, so it can never keep a server from starting.
     */
    public static void runSelfTest() {
        try {
            boolean ok = true;
            // min / max helpers over member steps
            ok &= lowest(new int[]{2, 2, 2}) == 2;
            ok &= lowest(new int[]{5, 2, 4}) == 2;
            ok &= highest(new int[]{5, 2, 4}) == 5;
            // Aligned party, leader on the step, request == step: DMZ runs it, no takeover.
            ok &= !takeoverNeeded(2, 2, 2);
            // A member behind (target 2), an ahead member requests step 4: steer to 2.
            ok &= takeoverNeeded(4, 2, 4);
            // Request is exactly the lowest step but the leader is ahead of it: DMZ would say "already active", steer.
            ok &= takeoverNeeded(2, 2, 5);
            // Request below the party's lowest step (a quest everyone finished): leave DMZ to answer.
            ok &= !takeoverNeeded(1, 2, 2);
            if (ok) {
                DmzNpc.LOGGER.info("[{}] party lowest-saga self-test PASSED.", DmzNpc.MODID);
            } else {
                DmzNpc.LOGGER.error("[{}] party lowest-saga self-test FAILED: lowest-step decision is wrong.",
                        DmzNpc.MODID);
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.error("[{}] party lowest-saga self-test errored: {}", DmzNpc.MODID, t.toString());
        }
    }

    private static int lowest(int[] steps) {
        int m = Integer.MAX_VALUE;
        for (int s : steps) {
            m = Math.min(m, s);
        }
        return steps.length == 0 ? 0 : m;
    }

    private static int highest(int[] steps) {
        int m = Integer.MIN_VALUE;
        for (int s : steps) {
            m = Math.max(m, s);
        }
        return steps.length == 0 ? 0 : m;
    }
}
