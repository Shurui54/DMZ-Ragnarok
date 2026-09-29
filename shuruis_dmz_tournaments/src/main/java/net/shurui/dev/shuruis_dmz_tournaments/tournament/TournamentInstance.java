package net.shurui.dev.shuruis_dmz_tournaments.tournament;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.shurui.dev.shuruis_dmz_tournaments.Config;
import net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter;
import net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData;
import net.shurui.dev.shuruis_dmz_tournaments.dmz.DmzHooks;
import net.shurui.dev.shuruis_dmz_tournaments.inventory.InventoryVault;
import net.shurui.dev.shuruis_dmz_tournaments.region.Region;
import net.shurui.dev.shuruis_dmz_tournaments.reward.RewardManager;
import net.shurui.dev.shuruis_dmz_tournaments.util.Announcer;
import net.shurui.dev.shuruis_dmz_tournaments.util.TextUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// one running (or idle) tournament, driven by its def. owns the roster, bracket, phase timers and all the
// ceremony (teleport, heal, friendly-fist, announce, rewards). config/bounds read live from the def so GUI
// edits apply immediately.
//
// competing unit is a Team: a 1v1 fighter is a one-member team, so SOLO/DUOS/TRIOS all run the same
// single-elim bracket. FFA (Tournament of Power) skips the bracket, one big match, last one standing wins.
public class TournamentInstance {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    // grace after the bell where a bout can't be decided, lets start-of-bout state settle
    private static final int MATCH_START_GRACE_TICKS = 20;
    // how close to the floor counts as touching it, in blocks. See touchingGround.
    private static final double GROUND_HOVER_TOLERANCE = 0.5;

    // client-facing view of a pending sign-up team
    public record TeamView(int id, String name, List<String> members) {}

    // a team being assembled during sign-up, before the bracket is built
    private static final class PendingTeam {
        final int id;
        String name;
        final List<UUID> members = new ArrayList<>();
        PendingTeam(int id, String name) { this.id = id; this.name = name; }
    }

    private final MinecraftServer server;
    private final String defId;

    private TournamentState state = TournamentState.IDLE;
    private final Set<UUID> signups = new LinkedHashSet<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Set<UUID> eliminated = new LinkedHashSet<>();
    private List<UUID> participants = new ArrayList<>();

    // team sign-up (team formats only)
    private final List<PendingTeam> pendingTeams = new ArrayList<>();
    private int nextPendingTeamId = 1;

    // finalized teams (all formats). for FFA these are 1-member teams
    private List<Team> teams = new ArrayList<>();
    private int nextTeamId = 1;

    private boolean ffaRun;
    private final Set<UUID> ffaAlive = new LinkedHashSet<>();       // FFA fighters still standing
    private final List<UUID> ffaEliminationOrder = new ArrayList<>();

    // tournament-character forced transform + invulnerability window (only when the feature is enabled).
    // both reset per bout; iframeUntil is keyed to the arena level's game time.
    private final Set<UUID> forcedTransform = new LinkedHashSet<>();
    private final Map<UUID, Long> iframeUntil = new HashMap<>();

    private Bracket bracket;
    private Match activeMatch;
    private final Set<UUID> downedThisMatch = new LinkedHashSet<>(); // members knocked out mid-bout
    // fighters who have left the ground since this bout began. Only they can be rung out by landing; see defeatReason.
    private final Set<UUID> tookOff = new LinkedHashSet<>();
    // fighters whose flight has already been cut once this bout, so the courtesy fall-reset is not a free cancel.
    private final Set<UUID> flightCut = new LinkedHashSet<>();
    private int phaseTimer;
    private int matchTicks;
    private long signupEndMillis;
    private int signupReminderTicks;   // counts up during SIGNUP, fires the repeat join prompt

    // where each player stood before joining, so we can send them home when the run ends
    private record ReturnPos(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim,
                             double x, double y, double z, float yaw, float pitch) {}
    private final Map<UUID, ReturnPos> returnPositions = new HashMap<>();

    public TournamentInstance(MinecraftServer server, String defId) {
        this.server = server;
        this.defId = defId;
    }

    public String defId() {
        return defId;
    }

    private TournamentDef def() {
        return TournamentData.get(server).getDef(defId);
    }

    public TournamentDef definition() {
        return def();
    }

    public TournamentState state() {
        return state;
    }

    public boolean isIdle() {
        return state == TournamentState.IDLE;
    }

    public boolean isSignupOpen() {
        return state == TournamentState.SIGNUP;
    }

    public boolean isSignedUp(UUID id) {
        return signups.contains(id);
    }

    public int signupCount() {
        return signups.size();
    }

    public TournamentFormat format() {
        TournamentDef def = def();
        return def == null ? TournamentFormat.SOLO : def.format();
    }

    // active = in a live match and not yet knocked out
    public boolean isActiveFighter(UUID id) {
        if (state != TournamentState.MATCH) return false;
        if (ffaRun) return ffaAlive.contains(id);
        return activeMatch != null && activeMatch.involves(id)
                && !downedThisMatch.contains(id) && !eliminated.contains(id);
    }

    // same team in the current bout (friendly-fire denial)
    public boolean sameActiveTeam(UUID a, UUID b) {
        if (state != TournamentState.MATCH || ffaRun || activeMatch == null) return false;
        Team ta = activeMatch.teamOf(a);
        Team tb = activeMatch.teamOf(b);
        return ta != null && ta == tb;
    }

    // still in this tournament (fighting or waiting for their bout)
    public boolean isContestant(UUID id) {
        return (state == TournamentState.MATCH || state == TournamentState.COUNTDOWN
                || state == TournamentState.INTERMISSION) && participants.contains(id) && !eliminated.contains(id);
    }

    public boolean openSignups(int minutes) {
        TournamentDef def = def();
        if (def == null || state != TournamentState.IDLE) return false;
        if (!def.hasAllRegions()) return false;
        if (!formatAllowed(def)) return false;
        signups.clear();
        names.clear();
        eliminated.clear();
        pendingTeams.clear();
        nextPendingTeamId = 1;
        state = TournamentState.SIGNUP;
        signupEndMillis = System.currentTimeMillis() + minutes * 60_000L;
        signupReminderTicks = 0;
        Announcer.announce(server, fmt(def.msgSignupOpen, def), "&eSign-ups Open!", def);
        Announcer.broadcastJoinPrompt(server, def, "&7» Click to enter &e" + def.name + "&7: ");
        // Tell the network this server now runs this tournament, so a join from anywhere routes here.
        TournamentNetwork.setHost(defId, "SIGNUP");
        return true;
    }

    public JoinResult join(ServerPlayer player) {
        TournamentDef def = def();
        if (def == null) return JoinResult.NO_SUCH_TOURNAMENT;
        if (state != TournamentState.SIGNUP) return JoinResult.CLOSED;
        if (signups.contains(player.getUUID())) return JoinResult.ALREADY;
        if (def.maxParticipants > 0 && signups.size() >= def.maxParticipants) return JoinResult.FULL;
        if (def.requireCharacter && !DmzHooks.hasCreatedCharacter(player)) return JoinResult.NO_CHARACTER;
        // Title-holder lockout, PER TITLE, checked at ENTRY only (never mid match). heldTitleIds reads the cross-shard
        // synced holder map, so a crown won on another shard bars entry here too. We refuse only when a title the
        // player holds is in this def's barring set, so a Tournament of Power can turn away a G.O.D. or an Angel while
        // the World Martial Arts champion still competes. A player who signs up first and only THEN wins a title keeps
        // their existing sign-up: the gate is the door, not a sweep, so no one is yanked out of a roster they already
        // made. The rotation still holds, because next cycle they are turned away at this same door.
        if (def.barsAny(TournamentData.get(server).heldTitleIds(player.getUUID())))
            return JoinResult.TITLE_HOLDER;
        signups.add(player.getUUID());
        names.put(player.getUUID(), player.getGameProfile().getName());
        returnPositions.put(player.getUUID(), new ReturnPos(player.serverLevel().dimension(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        // roster full, start now instead of idling out the timer with no one left to join
        if (def.maxParticipants > 0 && signups.size() >= def.maxParticipants) {
            Announcer.announce(server, "&6[" + def.name + "] &fRoster full - starting now!", "&6" + def.name + "!", def);
            begin();
        }
        return JoinResult.OK;
    }

    /** The barring titles this player holds for this def, so a TITLE_HOLDER refusal can name them. Empty when none. */
    public java.util.List<String> barringTitlesFor(UUID player) {
        TournamentDef def = def();
        if (def == null) return java.util.Collections.emptyList();
        return def.barringTitles(TournamentData.get(server).heldTitleIds(player));
    }

    /** Shared TITLE_HOLDER refusal text, naming the offending title(s) when known so the player understands why. */
    public static String titleHolderRefusal(java.util.List<String> barringTitles) {
        if (barringTitles == null || barringTitles.isEmpty())
            return "&cTitle holders cannot enter this tournament. Pass your title on first.";
        return "&cYou hold a barred title (" + String.join(", ", barringTitles)
                + ") and cannot enter this tournament. Pass it on first.";
    }

    public boolean leave(UUID id) {
        removeFromPendingTeam(id);
        returnPositions.remove(id);
        return signups.remove(id);
    }

    public boolean createTeam(ServerPlayer player, String teamName) {
        if (!ensureSignedUp(player)) return false;
        if (!format().isTeam()) return false;
        removeFromPendingTeam(player.getUUID());
        PendingTeam t = new PendingTeam(nextPendingTeamId++,
                (teamName == null || teamName.isBlank()) ? (player.getGameProfile().getName() + "'s Team") : teamName.trim());
        t.members.add(player.getUUID());
        pendingTeams.add(t);
        return true;
    }

    public boolean joinTeam(ServerPlayer player, int teamId) {
        if (!ensureSignedUp(player)) return false;
        if (!format().isTeam()) return false;
        PendingTeam t = pendingTeam(teamId);
        if (t == null || t.members.size() >= format().teamSize()) return false;
        removeFromPendingTeam(player.getUUID());
        t.members.add(player.getUUID());
        return true;
    }

    public boolean leaveTeam(UUID id) {
        return removeFromPendingTeam(id);
    }

    public List<TeamView> teamViews() {
        List<TeamView> out = new ArrayList<>();
        for (PendingTeam t : pendingTeams) {
            List<String> members = new ArrayList<>();
            for (UUID m : t.members) members.add(nameOnly(m));
            out.add(new TeamView(t.id, t.name, members));
        }
        return out;
    }

    private boolean ensureSignedUp(ServerPlayer player) {
        if (signups.contains(player.getUUID())) return true;
        return join(player) == JoinResult.OK;
    }

    private PendingTeam pendingTeam(int id) {
        for (PendingTeam t : pendingTeams) if (t.id == id) return t;
        return null;
    }

    private boolean removeFromPendingTeam(UUID id) {
        boolean removed = false;
        for (PendingTeam t : pendingTeams) removed |= t.members.remove(id);
        pendingTeams.removeIf(t -> t.members.isEmpty());
        return removed;
    }

    public boolean forceStart() {
        if (state != TournamentState.SIGNUP) return false;
        begin();
        return true;
    }

    // Every tournament format is public as of 2.0 (owner decision): 2v2, 3v3 and FFA no longer require a key.
    private boolean formatAllowed(TournamentDef def) {
        return true;
    }

    private void begin() {
        TournamentDef def = def();
        TournamentFormat fmt = def.format();
        if (!formatAllowed(def)) {
            // safety net: a gated format reached start without the key (e.g. via scheduling), bail cleanly
            Announcer.broadcast(server, "&c[" + def.name + "] " + fmt.label()
                    + " tournaments require Shurui's Key on the server. Cancelled.");
            resetToIdle();
            return;
        }
        List<Team> built = buildTeams(def, fmt);
        int minTeams = Math.max(2, fmt.isTeam() ? 2 : def.minParticipants);
        if (built.size() < minTeams) {
            Announcer.broadcast(server, "&c[" + def.name + "] Not enough fighters. Cancelled.");
            resetToIdle();
            return;
        }
        teams = built;
        participants = new ArrayList<>();
        for (Team t : teams) participants.addAll(t.members);
        eliminated.clear();
        downedThisMatch.clear();
        tookOff.clear();
        flightCut.clear();
        forcedTransform.clear();
        iframeUntil.clear();
        if (tournamentCharsEnabled()) {
            // dedicated tournament character: swap each contestant into their frozen fighter (this replaces the
            // inventory vault entirely, since the tournament character carries no items of its own)
            for (UUID id : participants) {
                ServerPlayer p = player(id);
                if (p != null) TournamentCharacter.enter(p, def.id);
            }
        } else if (!def.itemsAllowed) {
            // items disallowed: stash each contestant's inventory + curios (armor stays on)
            for (UUID id : participants) {
                ServerPlayer p = player(id);
                if (p != null) InventoryVault.store(p);
            }
        }
        Announcer.announce(server, fmt(def.msgStarting, def).replace("{count}", String.valueOf(participants.size())),
                "&6" + def.name + "!", def);
        // Sign-ups are over: mark the tournament as running so a late join is no longer routed here.
        TournamentNetwork.setHost(defId, "RUNNING");

        ffaRun = fmt.isFfa();
        if (ffaRun) {
            beginFfa(def);
        } else {
            bracket = new Bracket(teams, true);
            prepareNextMatch();
        }
        parkNonFightersInWaiting(def);
    }

    // first match is already placed in the arena; send everyone else to the waiting area until
    // prepareNextMatch pulls them in. FFA places everyone at once so this moves no one. no-op if no waiting region.
    private void parkNonFightersInWaiting(TournamentDef def) {
        if (def.waiting == null) return;
        for (UUID id : participants) {
            if (isInActiveMatch(id)) continue;
            ServerPlayer p = player(id);
            if (p != null) teleport(p, def.waiting);
        }
    }

    private boolean isInActiveMatch(UUID id) {
        if (ffaRun) return ffaAlive.contains(id);
        return activeMatch != null && activeMatch.involves(id);
    }

    // turn the sign-up roster into the final list of competing teams
    private List<Team> buildTeams(TournamentDef def, TournamentFormat fmt) {
        List<Team> result = new ArrayList<>();
        nextTeamId = 1;
        if (!fmt.isTeam()) {
            // SOLO / FFA: one team per fighter
            for (UUID id : signups) result.add(Team.solo(nextTeamId++, id, nameOnly(id)));
            return result;
        }
        int size = fmt.teamSize();
        // pre-made teams first (drop offline / no-longer-signed-up members), keep captain's name
        List<PendingTeam> forming = new ArrayList<>();
        Set<UUID> assigned = new LinkedHashSet<>();
        for (PendingTeam t : pendingTeams) {
            PendingTeam f = new PendingTeam(0, t.name);
            for (UUID m : t.members) if (signups.contains(m) && player(m) != null) f.members.add(m);
            if (!f.members.isEmpty()) { forming.add(f); assigned.addAll(f.members); }
        }
        // remaining signed-up solos
        List<UUID> solos = new ArrayList<>();
        for (UUID id : signups) if (!assigned.contains(id) && player(id) != null) solos.add(id);
        if (def.allowRandomFill) {
            Collections.shuffle(solos);
            // top up partial pre-made teams first (they keep their name)
            for (PendingTeam f : forming) {
                while (f.members.size() < size && !solos.isEmpty()) f.members.add(solos.remove(0));
            }
            // group the rest into fresh unnamed teams
            while (solos.size() >= size) {
                PendingTeam f = new PendingTeam(0, null);
                for (int i = 0; i < size; i++) f.members.add(solos.remove(0));
                forming.add(f);
            }
        }
        // keep only full teams, tell anyone left short
        for (PendingTeam f : forming) {
            if (f.members.size() == size) {
                String tn = (f.name != null && !f.name.isBlank()) ? f.name : (nameOnly(f.members.get(0)) + "'s Team");
                result.add(new Team(nextTeamId++, tn, f.members));
            } else {
                for (UUID m : f.members) {
                    ServerPlayer p = player(m);
                    if (p != null) p.sendSystemMessage(TextUtil.color("&cNot enough players to form a full team - you were dropped."));
                }
            }
        }
        return result;
    }

    private void prepareNextMatch() {
        TournamentDef def = def();
        activeMatch = bracket.nextMatch();
        downedThisMatch.clear();
        tookOff.clear();
        flightCut.clear();
        forcedTransform.clear();
        iframeUntil.clear();
        if (activeMatch == null) {
            finishBracket();
            return;
        }
        ServerLevel level = server.getLevel(def.arena.dimension());
        boolean aLive = anyOnline(activeMatch.a);
        boolean bLive = anyOnline(activeMatch.b);
        if (!aLive && !bLive) { resolveMatch(activeMatch.a); return; }
        if (!aLive) { resolveMatch(activeMatch.b); return; }
        if (!bLive) { resolveMatch(activeMatch.a); return; }

        placeTeam(activeMatch.a, true, level, def);
        placeTeam(activeMatch.b, false, level, def);

        Announcer.announceTo(server, participants, fmt(def.msgMatch, def)
                .replace("{p1}", teamName(activeMatch.a)).replace("{p2}", teamName(activeMatch.b)), "&eFight!", def);
        matchTicks = 0;
        phaseTimer = Math.max(1, def.countdownSeconds) * 20;
        state = TournamentState.COUNTDOWN;
    }

    private void placeTeam(Team team, boolean sideA, ServerLevel level, TournamentDef def) {
        int count = team.size();
        for (int i = 0; i < team.members.size(); i++) {
            ServerPlayer p = player(team.members.get(i));
            if (p == null) continue;
            Vec3 pos = def.arena.sideSpawn(sideA, i, count);
            Vec3 facing = def.arena.sideSpawn(!sideA, i, count);
            placeFighter(p, level, pos, facing, def);
        }
    }

    private void placeFighter(ServerPlayer p, ServerLevel level, Vec3 pos, Vec3 facing, TournamentDef def) {
        // Yaw is worked out from the raw X/Z (which face the far wall); the Y is then snapped to a real standing spot so
        // a low-drawn arena box never buries the fighter under the floor (bug 640).
        float yaw = (float) Math.toDegrees(Math.atan2(facing.z - pos.z, facing.x - pos.x)) - 90f;
        pos = def.arena.snapToStand(level, pos);
        p.teleportTo(level, pos.x, pos.y, pos.z, yaw, 0);
        if (def.healAfterFight) DmzHooks.fullHeal(p);
        if (def.forceFriendlyFist) DmzHooks.setFriendlyFist(p, true);
    }

    private void beginFfa(TournamentDef def) {
        ffaAlive.clear();
        ffaEliminationOrder.clear();
        for (Team t : teams) ffaAlive.add(t.first());
        placeFfaFighters(def);
        Announcer.announceTo(server, participants, "&6[" + def.name + "] &fTournament of Power - " + ffaAlive.size() + " fighters!", "&6Battle Royale!", def);
        matchTicks = 0;
        phaseTimer = Math.max(1, def.countdownSeconds) * 20;
        state = TournamentState.COUNTDOWN;
    }

    private void placeFfaFighters(TournamentDef def) {
        ServerLevel level = server.getLevel(def.arena.dimension());
        for (UUID id : ffaAlive) {
            ServerPlayer p = player(id);
            if (p == null) continue;
            // randomStandableInside, not randomInside: a low-drawn arena box would otherwise scatter the battle royale
            // under the floor (bug 640).
            Vec3 pos = def.arena.randomStandableInside(level, p.getRandom());
            p.teleportTo(level, pos.x, pos.y, pos.z, p.getYRot(), 0);
            if (def.healAfterFight) DmzHooks.fullHeal(p);
            if (def.forceFriendlyFist) DmzHooks.setFriendlyFist(p, true);
        }
    }

    public void tick() {
        switch (state) {
            case IDLE -> { }
            case SIGNUP -> {
                if (System.currentTimeMillis() >= signupEndMillis) begin();
                else tickSignupReminder();
            }
            case COUNTDOWN -> tickCountdown();
            case MATCH -> { if (ffaRun) tickFfa(); else tickMatch(); }
            case INTERMISSION -> {
                if (--phaseTimer <= 0) prepareNextMatch();
            }
            case FINISHED -> {
                if (--phaseTimer <= 0) resetToIdle();
            }
        }
    }

    // re-pop the "Sign-ups Open" title + join prompt every N seconds while open
    private void tickSignupReminder() {
        TournamentDef def = def();
        if (def == null || def.signupReminderSeconds <= 0) return;
        if (++signupReminderTicks < def.signupReminderSeconds * 20) return;
        signupReminderTicks = 0;
        long secsLeft = Math.max(0, (signupEndMillis - System.currentTimeMillis()) / 1000);
        Announcer.title(server, "&eSign-ups Open!", "&6" + def.name, def);
        Announcer.broadcastJoinPrompt(server, def, "&e[" + def.name + "] &fSign-ups still open &7(" + signups.size()
                + " in, " + formatDuration(secsLeft) + " left)&f: ");
    }

    private void tickCountdown() {
        if (phaseTimer % 20 == 0) {
            int secs = phaseTimer / 20;
            if (secs > 0) Announcer.broadcastTo(server, participants, "&e" + secs + "...");
        }
        if (--phaseTimer <= 0) {
            // re-place and heal at the bell so countdown positioning/damage/knockdown doesn't carry in
            // and instantly end the match
            TournamentDef def = def();
            if (ffaRun) {
                placeFfaFighters(def);
            } else {
                ServerLevel level = server.getLevel(def.arena.dimension());
                placeTeam(activeMatch.a, true, level, def);
                placeTeam(activeMatch.b, false, level, def);
            }
            state = TournamentState.MATCH;
            matchTicks = 0;
            Announcer.announceTo(server, participants, "&6[" + def.name + "] FIGHT!", "&cFIGHT!", def);
        }
    }

    private void tickMatch() {
        TournamentDef def = def();
        matchTicks++;
        Region arena = def.arena;

        if (matchTicks > MATCH_START_GRACE_TICKS) {
            scanTeam(activeMatch.a, arena, def);
            scanTeam(activeMatch.b, arena, def);
            boolean aDown = teamDown(activeMatch.a);
            boolean bDown = teamDown(activeMatch.b);
            if (aDown || bDown) {
                Team winner = bDown ? activeMatch.a : activeMatch.b; // both fell -> side A wins
                LOGGER.info("[{}] Match resolved on tick {} - {} wins", def.id, matchTicks, teamName(winner));
                resolveMatch(winner);
                return;
            }
        }
        int limit = def.matchTimeLimit;
        if (limit > 0 && matchTicks >= limit * 20) {
            resolveMatch(teamHealth(activeMatch.a) >= teamHealth(activeMatch.b) ? activeMatch.a : activeMatch.b);
        }
    }

    // knock out newly-defeated members; team survives while any remain
    private void scanTeam(Team team, Region arena, TournamentDef def) {
        for (UUID id : team.members) {
            if (downedThisMatch.contains(id)) continue;
            ServerPlayer p = player(id);
            trackFlight(p, def);
            String reason = defeatReason(p, arena, def);
            if (reason != null) {
                downedThisMatch.add(id);
                knockOutMember(id, def, reason);
            }
        }
    }

    private void tickFfa() {
        TournamentDef def = def();
        matchTicks++;
        Region arena = def.arena;
        if (matchTicks > MATCH_START_GRACE_TICKS) {
            for (UUID id : new ArrayList<>(ffaAlive)) {
                ServerPlayer p = player(id);
                trackFlight(p, def);
                String reason = defeatReason(p, arena, def);
                if (reason != null) {
                    ffaAlive.remove(id);
                    ffaEliminationOrder.add(id);
                    knockOutMember(id, def, reason);
                    Announcer.announceTo(server, participants, "&6[" + def.name + "] &e" + nameOnly(id)
                            + " &fis out! &7(" + ffaAlive.size() + " left)", null, def);
                }
            }
            if (ffaAlive.size() <= 1) { finishFfa(); return; }
        }
        int limit = def.matchTimeLimit;
        if (limit > 0 && matchTicks >= limit * 20) {
            // rank survivors by health, highest is champion
            List<UUID> alive = new ArrayList<>(ffaAlive);
            alive.sort((x, y) -> Float.compare(healthOf(y), healthOf(x)));
            for (int i = alive.size() - 1; i >= 1; i--) ffaEliminationOrder.add(alive.get(i));
            ffaAlive.clear();
            if (!alive.isEmpty()) ffaAlive.add(alive.get(0));
            finishFfa();
        }
    }

    // per-fighter knockout: heal, send to stands, hand items back, drop friendly-fist
    private void knockOutMember(UUID id, TournamentDef def, String reason) {
        eliminate(id);
        ServerPlayer p = player(id);
        if (p != null) {
            if (def.healAfterFight) DmzHooks.fullHeal(p);
            teleport(p, def.stands);
            DmzHooks.setFriendlyFist(p, false);
            restoreFighter(p);
        }
        LOGGER.info("[{}] {} knocked out ({})", def.id, nameOnly(id), reason);
    }

    /**
     * Keep this fighter's flight matching the rule and remember whether they have left the ground. Called per
     * fighter per tick BEFORE the defeat check, so the take-off latch is current when the ground rule is read.
     * Split from {@code defeatReason} on purpose: that answers a question, this changes things.
     */
    private void trackFlight(ServerPlayer p, TournamentDef def) {
        if (p == null) return;
        if (!def.flightAllowed) {
            // every tick, not once at the bell: flight is a skill the player can toggle back on at any moment.
            if (DmzHooks.isFlying(p)) {
                DmzHooks.stopFlying(p);
                // FIRST cut only. Someone hovering at the bell did not choose to fall, so is not charged for the
                // drop; doing it every cut would make the fly key a fall-damage cancel.
                if (flightCut.add(p.getUUID())) p.fallDistance = 0.0f;
            }
            return;
        }
        if (def.groundOut && !touchingGround(p)) tookOff.add(p.getUUID());
    }

    /**
     * Is this fighter on the floor? NOT {@code onGround()}, which a DMZ flier never reports: combat flight holds a
     * player a fraction clear of whatever is under them, so someone who flew out of the arena and settled above the
     * grass was never "on the ground" and could sit out of bounds indefinitely (only cutting flight ever finished a
     * ring-out, so escaping one was a matter of not pressing a key). Instead ask whether something solid is
     * immediately beneath them; the tolerance covers a hover gap we do not control, small enough that flying about
     * above the floor is nothing like touching it.
     */
    private static boolean touchingGround(ServerPlayer p) {
        if (p.onGround()) return true;
        return !p.level().noCollision(p, p.getBoundingBox().move(0.0, -GROUND_HOVER_TOLERANCE, 0.0));
    }

    // reason this fighter is defeated, or null if still fighting
    private String defeatReason(ServerPlayer p, Region arena, TournamentDef def) {
        if (p == null) return "logged off";
        // ring-out only counts once they are down outside, not mid-air, so someone knocked flying out can recover
        if (def.ringOut && touchingGround(p) && !arena.contains(p)) return "left the arena";
        // FLOOR-OUT is deliberately the OPPOSITE case to ring-out: it does NOT wait for touchingGround. Ring-out
        // holds off until a fighter is down so a leap over the edge is survivable, but a Tournament of Power style
        // pit kills the instant you pass the line, mid-air included, exactly as being knocked into the void should.
        // Do not "tidy" this into a shared touchingGround guard; the missing check is the whole point.
        if (def.floorOut && p.getY() < def.floorOutY) return "fell below the ring";
        // THE FLOOR IS OUT OF BOUNDS, but only once a fighter has taken off. Everyone starts standing on it, so a
        // plain "on the ground" rule would eliminate the whole field at the bell (and anyone who never had flight).
        // Taking off is the opt-in.
        if (def.groundOut && def.flightAllowed && touchingGround(p) && tookOff.contains(p.getUUID()))
            return "touched the ground";
        if (p.isDeadOrDying()) return "died";
        if (p.getHealth() <= 1.0f) return "health depleted";
        if (DmzHooks.stats(p).map(s -> s.getStatus().isKnockedDown()).orElse(false)) return "knocked down";
        return null;
    }

    private boolean teamDown(Team team) {
        for (UUID id : team.members) if (!downedThisMatch.contains(id)) return false;
        return true;
    }

    private boolean anyOnline(Team team) {
        if (team == null) return false;
        for (UUID id : team.members) if (player(id) != null) return true;
        return false;
    }

    private float teamHealth(Team team) {
        float sum = 0;
        for (UUID id : team.members) sum += healthOf(id);
        return sum;
    }

    private float healthOf(UUID id) {
        ServerPlayer p = player(id);
        return (p != null && p.getMaxHealth() > 0) ? p.getHealth() / p.getMaxHealth() : 0f;
    }

    private void resolveMatch(Team winner) {
        TournamentDef def = def();
        bracket.completeMatch(activeMatch, winner);
        Team loser = activeMatch.opponentOf(winner);

        // winners advance: heal and send to waiting
        for (UUID id : winner.members) {
            ServerPlayer p = player(id);
            if (p != null) {
                if (def.healAfterFight) DmzHooks.fullHeal(p);
                teleport(p, def.waiting);
            }
        }
        // losers out: items back, send to stands (unless already knocked out mid-bout)
        if (loser != null) {
            for (UUID id : loser.members) {
                eliminate(id);
                if (!downedThisMatch.contains(id)) {
                    ServerPlayer p = player(id);
                    if (p != null) {
                        if (def.healAfterFight) DmzHooks.fullHeal(p);
                        teleport(p, def.stands);
                        DmzHooks.setFriendlyFist(p, false);
                        restoreFighter(p);
                    }
                }
            }
        }
        Announcer.announceTo(server, participants, fmt(def.msgMatchWin, def)
                .replace("{winner}", teamName(winner)).replace("{loser}", teamName(loser)), null, def);
        phaseTimer = 5 * 20;
        state = TournamentState.INTERMISSION;
    }

    private void eliminate(UUID id) {
        if (id != null) eliminated.add(id);
    }

    private void finishBracket() {
        awardAndFinish(bracket.champion(), bracket.runnerUp());
    }

    private void finishFfa() {
        UUID championId = ffaAlive.isEmpty()
                ? (ffaEliminationOrder.isEmpty() ? null : ffaEliminationOrder.get(ffaEliminationOrder.size() - 1))
                : ffaAlive.iterator().next();
        UUID runnerId = null;
        if (!ffaEliminationOrder.isEmpty()) {
            UUID last = ffaEliminationOrder.get(ffaEliminationOrder.size() - 1);
            runnerId = (championId != null && championId.equals(last) && ffaEliminationOrder.size() >= 2)
                    ? ffaEliminationOrder.get(ffaEliminationOrder.size() - 2) : last;
        }
        Team champ = championId == null ? null : teamOfMember(championId);
        Team runner = runnerId == null ? null : teamOfMember(runnerId);
        awardAndFinish(champ, runner);
    }

    private void awardAndFinish(Team champion, Team runnerUp) {
        TournamentDef def = def();
        String champLabel = champion == null ? "(nobody)" : teamName(champion);
        Announcer.announceTo(server, participants, fmt(def.msgChampion, def).replace("{champion}", champLabel),
                "&6" + champLabel + " wins!", def);

        Set<UUID> awarded = new LinkedHashSet<>();
        if (champion != null) {
            for (UUID id : champion.members) {
                awarded.add(id);
                ServerPlayer p = player(id);
                if (p != null) {
                    DmzHooks.setFriendlyFist(p, false);
                    restoreFighter(p);
                    RewardManager.giveAll(server, p, def.winnerRewards);
                    giveEventBonus(p, def.id, 1);
                }
            }
        }
        if (runnerUp != null) {
            for (UUID id : runnerUp.members) {
                awarded.add(id);
                ServerPlayer p = player(id);
                if (p != null) {
                    RewardManager.giveAll(server, p, def.runnerUpRewards);
                    giveEventBonus(p, def.id, 2);
                }
            }
        }
        for (UUID id : participants) {
            if (awarded.contains(id)) continue;
            ServerPlayer p = player(id);
            if (p != null) {
                RewardManager.giveAll(server, p, def.participationRewards);
                giveEventBonus(p, def.id, 3);
            }
        }
        phaseTimer = 10 * 20;
        state = TournamentState.FINISHED;
    }

    // Event overlay: an active timed event (via the core sdu hook, never the key/SU directly) may add bonus reward
    // tokens for a tournament placement (1 = champion, 2 = runner-up, 3 = participant). Inert keyless / no event.
    private void giveEventBonus(ServerPlayer p, String defId, int placement) {
        java.util.List<String> bonus = net.shurui.dev.sdu.api.key.EventHooks.get()
                .tournamentBonusTokens(defId, placement);
        if (!bonus.isEmpty()) RewardManager.giveAll(server, p, bonus);
    }

    public void cancel() {
        if (state == TournamentState.IDLE) return;
        Announcer.broadcast(server, "&c[" + def().name + "] Tournament cancelled by an administrator.");
        for (UUID id : participants) {
            ServerPlayer p = player(id);
            if (p != null) {
                DmzHooks.setFriendlyFist(p, false);
                restoreFighter(p);
            }
        }
        resetToIdle();
    }

    // send everyone back to where they stood before joining, then forget those spots
    private void returnAll() {
        for (Map.Entry<UUID, ReturnPos> e : returnPositions.entrySet()) {
            ServerPlayer p = player(e.getKey());
            if (p == null) continue;
            ReturnPos r = e.getValue();
            ServerLevel level = server.getLevel(r.dim());
            if (level != null) p.teleportTo(level, r.x(), r.y(), r.z(), r.yaw(), r.pitch());
        }
        returnPositions.clear();
    }

    private void resetToIdle() {
        // swap back anyone still in a tournament character before we forget the roster, so a clean-up path
        // (too-few-fighters bail, gated-format bail, cancel) can never strand a fighter
        for (UUID id : participants) restoreFighter(player(id));
        returnAll();
        // Over on this server: drop it from the network host directory so nobody is routed to a finished tournament.
        TournamentNetwork.clearHost(defId);
        state = TournamentState.IDLE;
        signups.clear();
        participants.clear();
        eliminated.clear();
        pendingTeams.clear();
        teams = new ArrayList<>();
        bracket = null;
        activeMatch = null;
        downedThisMatch.clear();
        tookOff.clear();
        flightCut.clear();
        forcedTransform.clear();
        iframeUntil.clear();
        ffaRun = false;
        ffaAlive.clear();
        ffaEliminationOrder.clear();
    }

    public int teleportSignupsToWaiting() {
        TournamentDef def = def();
        if (def == null || def.waiting == null) return 0;
        int moved = 0;
        Set<UUID> targets = (state == TournamentState.SIGNUP) ? signups : new LinkedHashSet<>(participants);
        for (UUID id : targets) {
            ServerPlayer p = player(id);
            if (p != null) { teleport(p, def.waiting); moved++; }
        }
        return moved;
    }

    // used by the sign-up GUI/command
    public boolean teleportToWaiting(ServerPlayer p) {
        TournamentDef def = def();
        if (def == null || def.waiting == null) return false;
        teleport(p, def.waiting);
        return true;
    }

    private void teleport(ServerPlayer p, Region region) {
        ServerLevel level = server.getLevel(region.dimension());
        // randomStandableInside, not randomInside: the latter answers the selection's floor plus one, which buries
        // whoever is sent there when the box was drawn a few blocks low (under bedrock in a full-height selection).
        Vec3 pos = region.randomStandableInside(level, p.getRandom());
        p.teleportTo(level, pos.x, pos.y, pos.z, p.getYRot(), p.getXRot());
    }

    private ServerPlayer player(UUID id) {
        return id == null ? null : server.getPlayerList().getPlayer(id);
    }

    // this tournament uses tournament characters only when BOTH the global master switch and this def's own toggle
    // are on. A def with the toggle off never swaps a contestant's character; they fight as themselves.
    private boolean tournamentCharsEnabled() {
        if (!Config.TOURNAMENT_CHARS_ENABLED.get()) return false;
        TournamentDef def = def();
        return def != null && def.tournamentChars;
    }

    // called wherever a fighter leaves the tournament (knockout, match loss, champion, cancel, reset): swap the
    // real character back when the tournament-character feature is on, else hand the stashed inventory back.
    private void restoreFighter(ServerPlayer p) {
        if (p == null) return;
        forcedTransform.remove(p.getUUID());
        iframeUntil.remove(p.getUUID());
        if (TournamentCharacter.isActive(p)) {
            TournamentCharacter.exit(p);
        } else {
            InventoryVault.restore(p);
        }
    }

    // true while the given fighter is inside their forced-transform invulnerability window
    public boolean isInIframe(UUID id, long gameTime) {
        Long until = iframeUntil.get(id);
        return until != null && gameTime < until;
    }

    // half-health forced transform: fires once per bout for an active fighter in a tournament character. Applies
    // the forced form + stock multiplier and opens the damage-immunity window read by isInIframe.
    public void checkForcedTransform(ServerPlayer p, float remainingHealth) {
        if (p == null || !tournamentCharsEnabled() || !Config.FORCE_TRANSFORM_AT_HALF.get()) return;
        if (!isActiveFighter(p.getUUID()) || !TournamentCharacter.isActive(p)) return;
        if (forcedTransform.contains(p.getUUID())) return;
        if (p.getMaxHealth() <= 0 || remainingHealth > p.getMaxHealth() * 0.5f) return;
        forcedTransform.add(p.getUUID());
        int iframe = Config.FORCED_TRANSFORM_IFRAME_TICKS.get();
        if (iframe > 0) iframeUntil.put(p.getUUID(), p.level().getGameTime() + iframe);
        TournamentCharacter.forceTransform(p);
    }

    private Team teamOfMember(UUID id) {
        for (Team t : teams) if (t.contains(id)) return t;
        return id == null ? null : Team.solo(0, id, nameOnly(id));
    }

    // team name for team formats, else the fighter's player name
    private String teamName(Team team) {
        if (team == null) return "(bye)";
        if (format().isTeam()) return team.displayName();
        return nameOnly(team.first());
    }

    private String nameOnly(UUID id) {
        if (id == null) return "?";
        ServerPlayer p = player(id);
        if (p != null) return p.getGameProfile().getName();
        return names.getOrDefault(id, id.toString().substring(0, 8));
    }

    private String fmt(String template, TournamentDef def) {
        return template.replace("{name}", def.name).replace("{id}", def.id).replace("{npc}", def.npcName);
    }

    // H:MM:SS, drop hours block when zero (M:SS). days fold into hours. negatives clamp to 0
    private static String formatDuration(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    public Component statusSummary() {
        TournamentDef def = def();
        String fmtLabel = def != null ? def.format().label() : "?";
        StringBuilder sb = new StringBuilder("&6[" + (def != null ? def.name : defId) + "] &7(" + fmtLabel + ") &fState: &e" + state);
        if (state == TournamentState.IDLE && def != null && def.scheduleEnabled) {
            long next = Scheduler.nextRunMillis(def);
            if (next > 0) {
                long secs = Math.max(0, (next - System.currentTimeMillis()) / 1000);
                sb.append(" &f| Sign-ups open in &e").append(formatDuration(secs));
            }
        } else if (state == TournamentState.SIGNUP) {
            long secs = Math.max(0, (signupEndMillis - System.currentTimeMillis()) / 1000);
            sb.append(" &f| Signed up: &e").append(signups.size()).append(" &f| Closes in &e").append(formatDuration(secs));
        } else if (ffaRun) {
            sb.append(" &f| Fighters left: &e").append(ffaAlive.size()).append('/').append(participants.size());
        } else if (bracket != null) {
            sb.append(" &f| Round &e").append(bracket.roundNumber())
              .append(" &f| Teams left: &e").append(teams.size() - teamsEliminated());
            if (activeMatch != null) {
                sb.append(" &f| Fighting: &e").append(teamName(activeMatch.a)).append(" &fvs &e").append(teamName(activeMatch.b));
            }
        }
        return TextUtil.color(sb.toString());
    }

    private int teamsEliminated() {
        int n = 0;
        for (Team t : teams) {
            boolean allOut = true;
            for (UUID id : t.members) if (!eliminated.contains(id)) { allOut = false; break; }
            if (allOut) n++;
        }
        return n;
    }
}
