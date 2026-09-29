package net.shurui.dev.sdu.event;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.quest.QuestMobOwnership;
import net.shurui.dev.sdu.waypoint.Waypoint;
import net.shurui.dev.sdu.waypoint.WaypointMark;
import net.shurui.dev.sdu.waypoint.WaypointStore;

import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestObjective;
import com.dragonminez.common.quest.QuestRegistry;
import com.dragonminez.common.quest.QuestTextFormatter;
import com.dragonminez.common.quest.objectives.BiomeObjective;
import com.dragonminez.common.quest.objectives.CoordsObjective;
import com.dragonminez.common.quest.objectives.DimensionObjective;
import com.dragonminez.common.quest.objectives.InteractObjective;
import com.dragonminez.common.quest.objectives.KillObjective;
import com.dragonminez.common.quest.objectives.StructureObjective;
import com.dragonminez.common.stats.StatsData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server runtime keeping each player's HUD compass in sync. Once a second it assembles a player's active
 * waypoints (persisted manual ones from {@link WaypointStore} plus one marker for the current DMZ quest) and,
 * on change, pushes them to the client.
 *
 * <p>The quest marker comes from the tracked (or first accepted, still-incomplete) quest's current objective.
 * A {@code COORDS} objective is an explicit operator-authored pin and wins; otherwise point at the nearest
 * {@code STRUCTURE}/{@code BIOME}/kill-interact target. Recomputed from live quest state, so completing or
 * dropping the quest drops it from the next sync.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class WaypointTracker {

    // last-synced waypoint-set signature per player, so we only send on change
    private static final Map<UUID, String> LAST_SIG = new ConcurrentHashMap<>();
    /**
     * Cached structure/biome locate result, keyed {@code dimension#objectiveKey} and shared across players
     * so an absent structure is searched once, not per-player. Written by the async worker, read by the tick
     * thread; the tick thread never runs the locate itself.
     */
    private static final Map<String, LocateCache> LOCATE = new ConcurrentHashMap<>();

    /**
     * Single-thread executor running the expensive world-gen locates off the server thread. Single-threaded
     * on purpose: serializes locates (bounds load) and, since {@code findNearestMapStructure} reads the
     * level's non-thread-safe {@code StructureCheck} cache, we never fan out concurrent locates. Lazy, shut
     * down on {@link ServerStoppingEvent}.
     */
    private static volatile ExecutorService LOCATE_EXEC;

    private static final int STRUCTURE_SEARCH_RADIUS = 50;    // chunks (capped: bounds even an unlucky locate)
    private static final int BIOME_SEARCH_RADIUS = 6400;      // blocks
    private static final double ENTITY_SEARCH_RADIUS = 128.0; // blocks

    /**
     * How long a resolved-but-not-found (negative) result is trusted before we re-search. 30 minutes: a
     * "not found" means the structure/biome is not within the search radius of a fixed origin, which does not
     * change on any human timescale, and every retry blocks on STRUCTURE_STARTS chunk loads. The old 60 s
     * meant a quest pointing at an absent structure re-ran that whole world-gen search every minute, for
     * every player on that objective, for nothing.
     */
    private static final long NEGATIVE_COOLDOWN_MS = 30L * 60L * 1000L;
    /** Guard against re-enqueuing while a search is in flight but slow (executor backlog / hang). */
    private static final long PENDING_TIMEOUT_MS = 120_000L;

    private WaypointTracker() {
    }

    private static ExecutorService locateExec() {
        ExecutorService exec = LOCATE_EXEC;
        if (exec == null || exec.isShutdown()) {
            synchronized (WaypointTracker.class) {
                exec = LOCATE_EXEC;
                if (exec == null || exec.isShutdown()) {
                    exec = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "sdu-waypoint-locate");
                        t.setDaemon(true);
                        return t;
                    });
                    LOCATE_EXEC = exec;
                }
            }
        }
        return exec;
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
            sync(player, false);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] waypoint tick failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LAST_SIG.remove(player.getUUID());
            try {
                sync(player, true);
            } catch (Throwable ignored) {
                // first tick will retry
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SIG.remove(event.getEntity().getUUID());
        // LOCATE is keyed per (dimension, objective), shared across players - nothing to drop here.
    }

    /** Tear down the background locate executor and drop cached results when the server stops. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ExecutorService exec = LOCATE_EXEC;
        LOCATE_EXEC = null;
        if (exec != null) {
            exec.shutdownNow();
            try {
                exec.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        LOCATE.clear();
        LAST_SIG.clear();
    }

    /** Rebuild a player's waypoint set and push it if it changed (or {@code force}). Also called by commands. */
    public static void sync(ServerPlayer player, boolean force) {
        // S21: the manual pins, the global markers and the provider markers feed the waypoint compass and beacons,
        // which are private (OWNER-SPECS 4), so they come from the Ragnarok Key through WaypointHooks (keyless: none).
        // The quest marker is the public quest/objective location, so it is synced on every server; the quest tracker
        // HUD reads it, while the compass and beacon renderers stay hidden client side without the key. Same order as
        // before: manual pins, the quest marker, then the global and provider markers.
        List<Waypoint> list = new ArrayList<>(net.shurui.dev.sdu.api.key.WaypointHooks.get().manualPins(player));
        list.addAll(questWaypoints(player));
        list.addAll(net.shurui.dev.sdu.api.key.WaypointHooks.get().privateMarkers(player));
        String sig = signature(list);
        if (force || !sig.equals(LAST_SIG.get(player.getUUID()))) {
            LAST_SIG.put(player.getUUID(), sig);
            DmzNet.syncWaypointsToPlayer(player, list);
        }
    }

    private static String signature(List<Waypoint> list) {
        StringBuilder sb = new StringBuilder();
        for (Waypoint w : list) {
            sb.append(w.dim).append(':').append((int) w.x).append(',').append((int) w.y).append(',')
                    .append((int) w.z).append(',').append(w.name).append(',').append(w.color).append(',')
                    .append(w.icon).append(',').append(w.travel).append(',').append(w.beacon).append(',')
                    .append(w.note).append('|');
        }
        return sb.toString();
    }

    /**
     * A single marker for the mission the player is TRACKING, or none when they are on no quest at all.
     *
     * <p>Tracking is one at a time: DMZ stores a single {@code trackedQuestId}, so exactly one quest is pointed at
     * and switching the tracked quest REPLACES this marker rather than leaving the previous one on the compass and
     * the tracker. This used to return a row for every accepted quest at once, which is what left a player who
     * switched their tracked side mission looking at both the old and the new one; only the tracked mission is
     * synced now. With nothing explicitly tracked, {@link #pickQuest} falls back to the first accepted,
     * still-incomplete quest so a freshly accepted mission is never silent.
     *
     * <p>A quest that cannot be pointed at (a pure counter, like "train N times", which does not happen anywhere)
     * falls back to {@link #questNotice}, a positionless row carrying its objective and progress. Positionless is
     * the honest answer for a counter, and it is exactly what tasks and raid sign-ups already do.
     */
    private static List<Waypoint> questWaypoints(ServerPlayer player) {
        StatsData stats = DmzForms.stats(player);
        if (stats == null) {
            return List.of();
        }
        PlayerQuestData qd = stats.getPlayerQuestData();
        if (qd == null) {
            return List.of();
        }
        String questId = pickQuest(qd);
        if (questId == null) {
            return List.of();
        }
        Waypoint w = questWaypoint(player, qd, questId);
        if (w == null) {
            w = questNotice(qd, questId);
        }
        return w == null ? List.of() : List.of(w);
    }

    /**
     * The fallback row for a quest with nowhere to point: its current objective, and that objective's progress
     * where the counter is known.
     *
     * <p>The note is what the tracker draws in place of a distance, so it stays short and follows the shape the
     * task board already uses ({@code 3/10}). A quest whose progress cannot be read at all still gets a row with
     * {@code active}, because the point of this method is that being on a quest is never silent.
     *
     * <p>Returns null only when the quest id resolves to nothing in the registry, which means it is not a quest
     * any more and there is nothing truthful to say about it.
     */
    private static Waypoint questNotice(PlayerQuestData qd, String questId) {
        Quest quest = QuestRegistry.getQuest(questId);
        if (quest == null) {
            return null;
        }
        String name = quest.getTitle();
        String note = "active";
        List<QuestObjective> objectives = quest.getObjectives();
        if (objectives != null) {
            for (int i = 0; i < objectives.size(); i++) {
                if (isObjectiveDone(qd, quest, questId, i)) {
                    continue;
                }
                QuestObjective o = objectives.get(i);
                name = objectiveName(o, quest);
                try {
                    int required = quest.getObjectiveRequired(qd, questId, i);
                    if (required > 0) {
                        int progress = Math.min(qd.getObjectiveProgress(questId, i), required);
                        note = progress + "/" + required;
                    }
                } catch (Throwable ignored) {
                    // an unreadable counter leaves the generic note; the row still appears
                }
                break;
            }
        }
        return Waypoint.notice(name, markFor(quest), note);
    }

    private static Waypoint questWaypoint(ServerPlayer player, PlayerQuestData qd, String questId) {
        Quest quest = QuestRegistry.getQuest(questId);
        if (quest == null || quest.getObjectives() == null || quest.getObjectives().isEmpty()) {
            return null;
        }

        List<QuestObjective> objectives = quest.getObjectives();
        // COORDS is an explicit, authored pin - it overrides any structure/biome/kill target.
        for (int i = 0; i < objectives.size(); i++) {
            QuestObjective o = objectives.get(i);
            if (o instanceof CoordsObjective coords && !isObjectiveDone(qd, quest, questId, i)) {
                String dim = coordsDimension(objectives, player);
                BlockPos p = coords.getTargetPos();
                // beacon = true: a COORDS objective is a fixed, authored point, so it earns a world beam and pin.
                return Waypoint.quest(dim, p.getX(), p.getY(), p.getZ(), objectiveName(o, quest), markFor(quest),
                        true);
            }
        }
        // An incomplete DIMENSION objective the player is not standing in. Checked BEFORE the location search
        // below, because while the quest is still asking to be somewhere else, a target found in THIS dimension is
        // the wrong thing to point at: resolveLocation searches the player's current level, so it would happily
        // aim at the nearest local stand-in for an objective that is meant to be completed in another world.
        //
        // This is also the only marker such a step can ever produce. A DIMENSION objective has no coordinate at all
        // (see resolveLocation), so a quest on that step used to sync nothing and the compass simply vanished, with
        // no way for a player to tell an unfinished mission from a finished one.
        String currentDim = player.level().dimension().location().toString();
        for (int i = 0; i < objectives.size(); i++) {
            QuestObjective o = objectives.get(i);
            if (isObjectiveDone(qd, quest, questId, i) || !(o instanceof DimensionObjective d)) {
                continue;
            }
            String target = d.getDimensionId();
            if (target != null && !target.isBlank() && !target.equals(currentDim)) {
                return Waypoint.travelTo(target, objectiveName(o, quest), markFor(quest));
            }
        }

        // Otherwise the first incomplete objective that resolves to a location.
        for (int i = 0; i < objectives.size(); i++) {
            QuestObjective o = objectives.get(i);
            if (isObjectiveDone(qd, quest, questId, i)) {
                continue;
            }
            BlockPos p = resolveLocation(player, questId, i, o);
            if (p != null) {
                // beacon = false: a STRUCTURE/BIOME/KILL/INTERACT target is a searched or nearest-entity location
                // that can move, so it stays a compass-bar marker only and never raises a beam that chases an NPC.
                return Waypoint.quest(currentDim, p.getX(), p.getY(), p.getZ(), objectiveName(o, quest),
                        markFor(quest), false);
            }
        }
        return null;
    }

    /**
     * Which pin a quest's marker wears, straight off DMZ's own quest type.
     *
     * <p>Read from {@code getType()} rather than {@code isSideQuest()} because the type is the field that
     * actually distinguishes all four cases; the booleans only answer two of them. Worth knowing: DMZ 2.1.3 ships
     * 121 quests and every one of them is SAGA, so DAILY and EVENT only ever arrive from a datapack. A quest whose type is
     * somehow absent falls back to the main-story pin, which is the safe way to be wrong: a side quest shown as
     * main is a cosmetic mistake, a main quest shown as nothing is a player who cannot find the story.
     */
    private static WaypointMark markFor(Quest quest) {
        try {
            Quest.QuestType type = quest.getType();
            if (type == null) {
                return WaypointMark.MAIN;
            }
            return switch (type) {
                // Everything that is not the story line is BLUE. Daily and event quests were purple, on the
                // reasoning that a limited-time quest is the thing most worth calling out; purple is now reserved
                // for raid and tournament sign-ups, which are the only limited-time thing a player can MISS
                // entirely. A daily is still just a quest, so it reads as one.
                case SIDEQUEST, EVENT, DAILY -> WaypointMark.SIDE;
                default -> WaypointMark.MAIN;
            };
        } catch (Throwable t) {
            return WaypointMark.MAIN;
        }
    }

    /** The current objective's own description (what DMZ's quest HUD shows), falling back to the quest title. */
    private static String objectiveName(QuestObjective o, Quest quest) {
        try {
            String s = QuestTextFormatter.describeObjective(o).getString();
            if (s != null && !s.isBlank()) {
                return s;
            }
        } catch (Throwable ignored) {
            // fall back to the quest title
        }
        return quest.getTitle();
    }

    /**
     * The one quest to point at: the TRACKED quest when it is accepted and still incomplete, otherwise the first
     * accepted, still-incomplete quest.
     *
     * <p>Tracking is one at a time (DMZ stores a single {@code trackedQuestId}), so this returns a single id and the
     * compass and tracker only ever show that mission. The fallback keeps a freshly accepted, never-tracked quest
     * from being invisible, and mirrors the client tracker's own {@code pickQuest} so the HUD and the synced marker
     * always agree on which mission is shown.
     */
    private static String pickQuest(PlayerQuestData qd) {
        var accepted = qd.getAcceptedQuestIds();
        if (accepted == null || accepted.isEmpty()) {
            return null;
        }
        String tracked = qd.getTrackedQuestId();
        if (tracked != null && !tracked.isBlank() && accepted.contains(tracked) && !qd.isQuestCompleted(tracked)) {
            return tracked;
        }
        for (String id : accepted) {
            if (id != null && !id.isBlank() && !qd.isQuestCompleted(id)) {
                return id;
            }
        }
        return null;
    }

    private static boolean isObjectiveDone(PlayerQuestData qd, Quest quest, String questId, int index) {
        try {
            int progress = qd.getObjectiveProgress(questId, index);
            int required = quest.getObjectiveRequired(qd, questId, index);
            return required > 0 && progress >= required;
        } catch (Throwable t) {
            return false; // unknown -> treat as still active so we still point at it
        }
    }

    /**
     * Dimension for a COORDS pin: the quest's DIMENSION objective if it has one, else the dimension the player is
     * standing in.
     *
     * <p>The fallback used to be a hardcoded {@code minecraft:overworld}, which was wrong in the one case it was
     * there to cover. A COORDS objective carries a position and no world, so an authored pin on Namek with no
     * accompanying DIMENSION objective got stamped as the overworld; the compass then compared that against the
     * player's actual dimension, decided the mission was elsewhere, and told a player already standing on Namek to
     * travel to the Overworld, instead of pointing at the coordinates under their feet.
     *
     * <p>Current dimension is the right answer because bare coordinates mean "here": DMZ resolves such an objective
     * against whatever level the player is in, so the marker has to be stamped the same way or the bar and the
     * objective disagree. When the quest DOES name a dimension, that still wins, so a genuine cross-world pin keeps
     * its travel line.
     */
    private static String coordsDimension(List<QuestObjective> objectives, ServerPlayer player) {
        for (QuestObjective o : objectives) {
            if (o instanceof DimensionObjective dim && dim.getDimensionId() != null && !dim.getDimensionId().isBlank()) {
                return dim.getDimensionId();
            }
        }
        return player.level().dimension().location().toString();
    }

    private static BlockPos resolveLocation(ServerPlayer player, String questId, int index, QuestObjective o) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        String dim = level.dimension().location().toString();
        if (o instanceof StructureObjective s) {
            // EXPENSIVE world-gen search -> async, never on the tick thread.
            return cachedLocate(key(dim, "S", s.getStructureId()),
                    () -> locateStructure(level, origin, s.getStructureId()));
        }
        if (o instanceof BiomeObjective b) {
            // Also an expensive world-gen search -> async, never on the tick thread.
            return cachedLocate(key(dim, "B", b.getBiomeId()),
                    () -> locateBiome(level, origin, b.getBiomeId()));
        }
        if (o instanceof KillObjective k) {
            EntityType<?> type = safeType(k::resolveEntityType);
            // A QUEST-spawn kill mob belongs to the player who accepted it, and DMZ credits its death only to
            // that player's party. Pointing at the nearest mob OF THE TYPE ignored ownership, so on a busy
            // server the compass led a player to a stranger's identical quest boss (e.g. aMAIZEing99's Oozaru)
            // that gives them no credit. Scope a QUEST-spawn target to a mob this player could actually get
            // credit for; if none is nearby (their own was lost to a crash), point nowhere and fall back to the
            // positionless quest notice rather than a misleading pin. NATURAL-spawn kills are ordinary world
            // mobs with no owner, so they keep the plain nearest-of-type search.
            boolean ownedOnly = questOwnedKillTarget(k);
            return nearestEntity(level, player, type, ownedOnly); // live-tracked, cheap, stays synchronous
        }
        if (o instanceof InteractObjective in) {
            EntityType<?> type = EntityType.byString(
                    net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(in.getEntityTypeId())).orElse(null);
            return nearestEntity(level, player, type, false); // cheap, stays synchronous
        }
        return null; // DIMENSION / TALK_TO / ITEM / SKILL have no world coordinate to point at
    }

    private static String key(String dim, String tag, String arg) {
        return dim + "#" + tag + "#" + arg;
    }

    /**
     * Non-blocking accessor for an expensive locate result, called on the server tick thread. A resolved
     * cache hit (for a negative result, still within cooldown) returns immediately, possibly null (a
     * remembered "not found"). Otherwise returns null at once and, unless a search is pending or the negative
     * cooldown hasn't elapsed, enqueues the locate on the background executor; the waypoint appears a tick or
     * two later. Never runs {@code locate.get()} itself, so the tick can't block on world-gen.
     */
    private static BlockPos cachedLocate(String key, java.util.function.Supplier<BlockPos> locate) {
        long now = System.currentTimeMillis();
        LocateCache existing = LOCATE.get(key);
        if (existing != null) {
            if (existing.resolved) {
                BlockPos p = existing.pos.get();
                if (p != null) {
                    return p; // found: trust it (cache-until-server-stop)
                }
                // negative result ("not found"): honour the cooldown before re-searching
                if (now - existing.resolvedAt < NEGATIVE_COOLDOWN_MS) {
                    return null;
                }
            } else if (now - existing.enqueuedAt < PENDING_TIMEOUT_MS) {
                return null; // a search is already in flight; don't pile on
            }
        }
        // Need a (fresh) search. Atomically claim the slot: only the caller that installs OUR
        // pending entry actually enqueues the background locate; everyone else defers to it.
        LocateCache pending = new LocateCache(now);
        LocateCache active = LOCATE.compute(key, (k, cur) -> {
            if (cur == null) {
                return pending; // no entry -> we win
            }
            if (!cur.resolved) {
                // A search is pending: keep it unless it's stale (timed out), then replace.
                return (now - cur.enqueuedAt) < PENDING_TIMEOUT_MS ? cur : pending;
            }
            // Resolved: replace only if it was a not-found past its cooldown (positives never reach here).
            return (now - cur.resolvedAt) >= NEGATIVE_COOLDOWN_MS ? pending : cur;
        });
        if (active != pending) {
            return null; // someone else's search stands; wait for it
        }
        try {
            locateExec().execute(() -> {
                BlockPos pos = null;
                try {
                    pos = locate.get();
                } catch (Throwable t) {
                    DmzNpc.LOGGER.debug("[{}] async locate failed for {}: {}", DmzNpc.MODID, key, t.toString());
                }
                pending.resolve(pos);
            });
        } catch (Throwable t) {
            // Executor rejected (e.g. server stopping): drop the pending slot so it can retry later.
            LOCATE.remove(key, pending);
            DmzNpc.LOGGER.debug("[{}] could not enqueue locate for {}: {}", DmzNpc.MODID, key, t.toString());
        }
        return null; // not resolved yet - tick returns immediately
    }

    private static BlockPos locateStructure(ServerLevel level, BlockPos origin, String structureId) {
        if (structureId == null || structureId.isBlank()) {
            return null;
        }
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        HolderSet<Structure> targets;
        if (structureId.startsWith("#")) {
            TagKey<Structure> tag = TagKey.create(Registries.STRUCTURE, new ResourceLocation(structureId.substring(1)));
            Optional<HolderSet.Named<Structure>> named = reg.getTag(tag);
            if (named.isEmpty()) {
                return null;
            }
            targets = named.get();
        } else {
            Optional<Holder.Reference<Structure>> holder =
                    reg.getHolder(ResourceKey.create(Registries.STRUCTURE, new ResourceLocation(structureId)));
            if (holder.isEmpty()) {
                return null;
            }
            targets = HolderSet.direct(holder.get());
        }
        Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, targets, origin, STRUCTURE_SEARCH_RADIUS, false);
        return found == null ? null : found.getFirst();
    }

    private static BlockPos locateBiome(ServerLevel level, BlockPos origin, String biomeId) {
        if (biomeId == null || biomeId.isBlank()) {
            return null;
        }
        ResourceLocation rl = new ResourceLocation(biomeId);
        Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(
                h -> h.is(rl), origin, BIOME_SEARCH_RADIUS, 32, 64);
        return found == null ? null : found.getFirst();
    }

    private static BlockPos nearestEntity(ServerLevel level, ServerPlayer player, EntityType<?> type,
                                          boolean questOwnedOnly) {
        if (type == null) {
            return null;
        }
        AABB box = player.getBoundingBox().inflate(ENTITY_SEARCH_RADIUS);
        // Same 128-block radius, but narrow the broad-phase class so the section index hands us far fewer
        // candidates to test. A non-MISC MobCategory is always a Mob (LivingEntity), so we can skip the dropped
        // items, XP orbs, projectiles and the like that a bare Entity.class scan walks; MISC covers both living
        // (armor stands) and non-living entities, so there we keep Entity.class. The type predicate is unchanged,
        // so the entity picked is identical either way, only the set we iterate to find it shrinks.
        Class<? extends Entity> scanClass =
                type.getCategory() != MobCategory.MISC ? LivingEntity.class : Entity.class;
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : level.getEntitiesOfClass(scanClass, box, e -> e.getType() == type && e.isAlive())) {
            // For a QUEST-spawn kill target, only a mob this player could get credit for counts: matching the
            // type alone would pin the compass to another player's identical quest mob.
            if (questOwnedOnly && !Boolean.TRUE.equals(QuestMobOwnership.creditsPartyOf(e, player))) {
                continue;
            }
            double d = e.distanceToSqr(player);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best == null ? null : best.blockPosition();
    }

    /**
     * True when a KILL objective's mob is DMZ-spawned and owned (spawn mode QUEST), so the compass must scope to
     * this player's own mob. Unknown / any failure reads as NOT owned, so the marker degrades to the plain
     * nearest-of-type search rather than losing the pin.
     */
    private static boolean questOwnedKillTarget(KillObjective k) {
        try {
            return k.getSpawnMode() == KillObjective.SpawnMode.QUEST;
        } catch (Throwable t) {
            return false;
        }
    }

    private static EntityType<?> safeType(java.util.function.Supplier<EntityType<?>> s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A cache slot for one locate key. Starts life as "pending" (a search is in flight); the async
     * worker calls {@link #resolve(BlockPos)} exactly once when done. Fields touched from both the
     * background worker and the tick thread are {@code volatile}/atomic so the tick sees the result
     * as soon as it lands. The stored {@link BlockPos} may be {@code null} (a remembered "not found").
     */
    private static final class LocateCache {
        final long enqueuedAt;
        final AtomicReference<BlockPos> pos = new AtomicReference<>(null);
        volatile boolean resolved;
        volatile long resolvedAt;

        LocateCache(long enqueuedAt) {
            this.enqueuedAt = enqueuedAt;
        }

        void resolve(BlockPos p) {
            this.pos.set(p);
            this.resolvedAt = System.currentTimeMillis();
            this.resolved = true;
        }
    }
}
