package net.shurui.dev.shuruis_raid_bosses.raid;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.shuruis_raid_bosses.data.RaidData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-wide registry of {@link RaidInstance}s, one per {@link RaidBossDef}. Ticks every instance, drives
 * scheduling, and routes boss-death events to the owning raid. Multiple raids run concurrently.
 */
public class RaidManager {
    private static RaidManager instance;

    private final MinecraftServer server;
    private final Map<String, RaidInstance> instances = new LinkedHashMap<>();

    /**
     * Runs that are NOT one-per-stored-def: a dimensional tear's private fight, keyed by a synthetic run
     * id. Kept apart from {@link #instances} (whose contract is one instance per def, created on demand and
     * kept forever), because several tears can fight the same boss in different arenas and each must vanish
     * when its run ends rather than linger as an idle instance the scheduler would try to start. Everything
     * walks both maps via {@link #allInstances}; nothing below iterates {@code instances} directly.
     */
    private final Map<String, RaidInstance> adHoc = new LinkedHashMap<>();

    /** Server-tick counter, so the schedule poll runs once a second instead of every tick. */
    private long scheduleTick;

    private RaidManager(MinecraftServer server) {
        this.server = server;
    }

    public static void init(MinecraftServer server) {
        instance = new RaidManager(server);
    }

    public static void clear() {
        instance = null;
    }

    public static RaidManager get() {
        return instance;
    }

    /**
     * Every raid instance that exists, read only. Exposed so outside features (e.g. the quest tracker
     * listing an open sign-up) can ask what raids are doing without the map or guessing ids.
     */
    public java.util.Collection<RaidInstance> instances() {
        return java.util.Collections.unmodifiableCollection(allInstances());
    }

    /**
     * Every live instance, per-def and ad-hoc. Snapshotted because a run that resolves mid-walk
     * unregisters itself, which would otherwise be a concurrent modification.
     */
    private java.util.List<RaidInstance> allInstances() {
        java.util.List<RaidInstance> all = new ArrayList<>(instances.values());
        all.addAll(adHoc.values());
        return all;
    }

    /** the runtime instance for a defined raid (creating if needed), or an ad-hoc run by its synthetic key */
    public RaidInstance instance(String defId) {
        if (defId == null) return null;
        String key = defId.toLowerCase();
        // ad-hoc first: its key is one no stored def can have, so it cannot shadow a real raid, and checking
        // it first routes a tear boss's death back to the tear rather than the scheduled raid
        RaidInstance run = adHoc.get(key);
        if (run != null) return run;
        if (RaidData.get(server).getDef(key) == null) return null;
        return instances.computeIfAbsent(key, k -> new RaidInstance(server, k));
    }

    /**
     * Register an ad-hoc run (a tear's fight) under its own key, so it ticks and enemy deaths stamped with
     * that key route back to it. The caller owns the run and must {@link #unregisterAdHoc} when it ends.
     *
     * @return false when the key is already taken, which the caller must treat as a refusal to start
     */
    public boolean registerAdHoc(String key, RaidInstance run) {
        if (key == null || key.isBlank() || run == null) return false;
        String k = key.toLowerCase();
        if (adHoc.containsKey(k) || instances.containsKey(k)) return false;
        adHoc.put(k, run);
        return true;
    }

    /** drop an ad-hoc run; safe to call more than once */
    public void unregisterAdHoc(String key) {
        if (key != null) adHoc.remove(key.toLowerCase());
    }

    public void tick() {
        // Schedule polling is once per second: the fire-window de-dup (Scheduler) spans minutes-to-hours, so
        // 1 s granularity cannot skip a slot, and it saves a LocalDateTime.now()/zone/parse pass per def per tick.
        if (scheduleTick++ % 20 == 0) {
            for (String defId : new ArrayList<>(RaidData.get(server).allDefs().keySet())) {
                RaidInstance inst = instance(defId);
                if (inst != null && inst.isIdle()) {
                    Scheduler.tickSchedule(this, server, defId);
                }
            }
        }
        // ad-hoc runs tick but never schedule: a tear's fight is started by the player who walked in, and
        // an idle one has ended and is about to be unregistered
        for (RaidInstance inst : allInstances()) {
            inst.tick();
        }
    }

    /** player died: whichever raid they're in marks them eliminated (arena lockout) */
    public void onPlayerDeath(net.minecraft.server.level.ServerPlayer player) {
        for (RaidInstance inst : allInstances()) {
            inst.onParticipantDeath(player);
        }
    }

    /**
     * Offer a freshly-spawned entity (a DMZ transformation form) to whichever active raid's arena it
     * appeared in, so the raid keeps tracking the boss across transformations. Returns true if adopted.
     */
    public boolean tryAdoptTransformedBoss(net.minecraft.world.entity.LivingEntity entity) {
        for (RaidInstance inst : allInstances()) {
            if (inst.adoptTransformed(entity)) return true;
        }
        return false;
    }

    /**
     * Offer an UNtracked saga-boss death to whichever ACTIVE raid's arena the death happened in, so a
     * final-form transform that died without ever being adopted still credits a stage death (and a victory)
     * instead of the tracked-but-vanished ghost timing out into a false failure. Only ever called for
     * entities NOT already tracked (death handler's isBoss guard), so it cannot double-count a tracked kill.
     */
    public boolean creditUntrackedBossKill(net.minecraft.world.entity.LivingEntity dead) {
        for (RaidInstance inst : allInstances()) {
            if (inst != null && inst.creditUntrackedBossKill(dead)) return true;
        }
        return false;
    }

    /** route an enemy death to whichever raid owns it (via the damage tracker) */
    public void onEntityDeath(UUID enemyId, UUID killerId) {
        String defId = RaidDamageTracker.defOf(enemyId);
        if (defId == null) return;
        RaidInstance inst = instance(defId);
        if (inst != null) inst.onEntityDeath(enemyId, killerId);
        else RaidDamageTracker.clear(enemyId);
    }

    public boolean openSignups(String defId, int minutes) {
        RaidInstance inst = instance(defId);
        return inst != null && inst.openSignups(minutes);
    }

    public boolean forceStart(String defId) {
        RaidInstance inst = instance(defId);
        return inst != null && inst.forceStart();
    }

    /** start a raid on demand (Raid Soul item), seeding the initiator as a participant */
    public boolean startImmediate(String defId, ServerPlayer initiator) {
        RaidInstance inst = instance(defId);
        return inst != null && inst.startImmediate(initiator);
    }

    public boolean cancel(String defId) {
        RaidInstance inst = instance(defId);
        if (inst == null) return false;
        inst.cancel();
        return true;
    }

    public JoinResult join(String defId, ServerPlayer player) {
        RaidInstance inst = instance(defId);
        return inst == null ? JoinResult.NO_SUCH_RAID : inst.join(player);
    }

    /** true if the player is signed up for or participating in any raid */
    public boolean isInAnyRaid(UUID id) {
        for (RaidInstance inst : allInstances()) {
            if (inst.isSignedUp(id) || inst.isParticipant(id)) return true;
        }
        return false;
    }

    /**
     * True if the point is inside any ACTIVE raid's arena. Cross-mod entry point, called REFLECTIVELY by
     * SU's npcregion spawner to suppress ambient spawns in a live arena. Dimension matched by
     * resource-location string; the arena is an X/Z column, so {@code y} never excludes a point on its own.
     */
    public boolean isInsideActiveRaidZone(String dimensionId, double x, double y, double z) {
        if (dimensionId == null) return false;
        for (RaidInstance inst : allInstances()) {
            if (inst == null || !inst.isBossFightActive()) continue;
            RaidBossDef def = inst.definition();
            if (def == null || def.arena == null) continue;
            if (def.arena.contains(dimensionId, x, y, z)) return true;
        }
        return false;
    }

    /**
     * True if the player is a participant in any ACTIVE raid. Like {@link #isInsideActiveRaidZone} but
     * keyed on roster membership, not position (a bounced/returned fighter is still a participant until the
     * raid ends). Called REFLECTIVELY by SU's npcregion bridge to know a player is mid-raid wherever they
     * stand.
     */
    public boolean isPlayerInActiveRaid(java.util.UUID id) {
        if (id == null) return false;
        for (RaidInstance inst : allInstances()) {
            if (inst != null && inst.isBossFightActive() && inst.isParticipant(id)) return true;
        }
        return false;
    }
}
