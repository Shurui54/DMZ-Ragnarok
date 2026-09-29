package net.shurui.dev.shuruis_dmz_tournaments.tournament;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// one TournamentInstance per def. ticks them, drives scheduling, answers cross-tournament queries.
// multiple tournaments can run at once in different places.
public class TournamentManager {
    private static TournamentManager instance;

    private final MinecraftServer server;
    private final Map<String, TournamentInstance> instances = new LinkedHashMap<>();

    /** Server-tick counter, so the schedule poll runs once a second instead of every tick. */
    private long scheduleTick;

    private TournamentManager(MinecraftServer server) {
        this.server = server;
    }

    public static void init(MinecraftServer server) {
        instance = new TournamentManager(server);
    }

    public static void clear() {
        instance = null;
    }

    public static TournamentManager get() {
        return instance;
    }

    /**
     * Every existing instance, read only. Exposed so outside features (the quest tracker listing an open sign-up)
     * can ask what tournaments are doing without the map or guessing ids for {@link #instance}.
     */
    public java.util.Collection<TournamentInstance> instances() {
        return java.util.Collections.unmodifiableCollection(instances.values());
    }

    public TournamentInstance instance(String defId) {
        if (defId == null) return null;
        String key = defId.toLowerCase();
        if (TournamentData.get(server).getDef(key) == null) return null;
        return instances.computeIfAbsent(key, k -> new TournamentInstance(server, k));
    }

    public void tick() {
        // auto-open sign-ups at the scheduled time. Polled once per second: the fire-window de-dup (Scheduler)
        // spans minutes-to-hours, so 1 s granularity cannot skip a slot, and it saves a
        // LocalDateTime.now()/zone pass and a key-list copy per def per tick.
        if (scheduleTick++ % 20 == 0) {
            for (String defId : new java.util.ArrayList<>(TournamentData.get(server).allDefs().keySet())) {
                TournamentInstance inst = instance(defId);
                if (inst != null && inst.isIdle()) {
                    Scheduler.tickSchedule(this, server, defId);
                }
            }
        }
        for (TournamentInstance inst : instances.values()) {
            inst.tick();
        }
    }

    public boolean openSignups(String defId, int minutes) {
        TournamentInstance inst = instance(defId);
        return inst != null && inst.openSignups(minutes);
    }

    public boolean forceStart(String defId) {
        TournamentInstance inst = instance(defId);
        return inst != null && inst.forceStart();
    }

    public boolean cancel(String defId) {
        TournamentInstance inst = instance(defId);
        if (inst == null) return false;
        inst.cancel();
        return true;
    }

    public JoinResult join(String defId, ServerPlayer player) {
        TournamentInstance inst = instance(defId);
        return inst == null ? JoinResult.NO_SUCH_TOURNAMENT : inst.join(player);
    }

    // instance where this player is currently fighting, or null
    public TournamentInstance activeFighterInstance(UUID id) {
        for (TournamentInstance inst : instances.values()) {
            if (inst.isActiveFighter(id)) return inst;
        }
        return null;
    }

    public boolean isActiveFighter(UUID id) {
        return activeFighterInstance(id) != null;
    }

    // same team in any live bout (friendly-fire denial)
    public boolean sameActiveTeam(UUID a, UUID b) {
        for (TournamentInstance inst : instances.values()) {
            if (inst.sameActiveTeam(a, b)) return true;
        }
        return false;
    }

    // signed up for or competing in any tournament
    public boolean isInAnyTournament(UUID id) {
        for (TournamentInstance inst : instances.values()) {
            if (inst.isSignedUp(id) || inst.isContestant(id)) return true;
        }
        return false;
    }
}
