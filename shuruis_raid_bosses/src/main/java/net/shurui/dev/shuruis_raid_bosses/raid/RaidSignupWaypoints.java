package net.shurui.dev.shuruis_raid_bosses.raid;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.waypoint.Waypoint;
import net.shurui.dev.sdu.waypoint.WaypointMark;
import net.shurui.dev.sdu.waypoint.WaypointProviders;

/**
 * Puts an OPEN raid sign-up on the quest tracker in the purple event colour. A sign-up window expires
 * while a player is looking away, and chat scrolls off.
 *
 * <p>A {@code notice}, not a positioned marker: signing up is a command, and the arena is where the
 * fight happens, not where a player needs to go now (nowhere). Sending them to the arena during
 * sign-ups would be wrong. The row also says whether this player is already in.
 */
public final class RaidSignupWaypoints {

    private RaidSignupWaypoints() {
    }

    /** register with sdu's waypoint sync, once at mod setup */
    public static void register() {
        WaypointProviders.register(RaidSignupWaypoints::forPlayer);
    }

    private static List<Waypoint> forPlayer(ServerPlayer player) {
        RaidManager mgr = RaidManager.get();
        if (mgr == null) {
            return List.of();
        }
        List<Waypoint> out = new ArrayList<>();
        for (RaidInstance inst : mgr.instances()) {
            if (!inst.isSignupOpen()) {
                continue;
            }
            RaidBossDef def = inst.definition();
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
