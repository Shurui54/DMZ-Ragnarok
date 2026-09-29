package net.shurui.dev.shuruis_dmz_tournaments.tournament;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.waypoint.Waypoint;
import net.shurui.dev.sdu.waypoint.WaypointMark;
import net.shurui.dev.sdu.waypoint.WaypointProviders;

/**
 * Puts an OPEN sign-up on the quest tracker in the purple event colour. Like the raid version: the window lasts
 * minutes and its only notice was a chat line that scrolls away, so a player mid-fight missed it. A {@code notice},
 * not a positioned marker, because joining is a command not a journey (the arena is where it will be FOUGHT, not
 * where you sign up).
 */
public final class TournamentSignupWaypoints {

    private TournamentSignupWaypoints() {
    }

    /** Register with sdu's waypoint sync. Called once, at mod setup. */
    public static void register() {
        WaypointProviders.register(TournamentSignupWaypoints::forPlayer);
    }

    private static List<Waypoint> forPlayer(ServerPlayer player) {
        TournamentManager mgr = TournamentManager.get();
        if (mgr == null) {
            return List.of();
        }
        List<Waypoint> out = new ArrayList<>();
        for (TournamentInstance inst : mgr.instances()) {
            if (!inst.isSignupOpen()) {
                continue;
            }
            TournamentDef def = inst.definition();
            if (def == null) {
                continue;
            }
            String note = inst.isSignedUp(player.getUUID())
                    ? "signed up"
                    : "sign-ups open, " + inst.signupCount() + " in";
            out.add(Waypoint.notice(def.name, WaypointMark.EVENT, note));
        }
        return out;
    }
}
