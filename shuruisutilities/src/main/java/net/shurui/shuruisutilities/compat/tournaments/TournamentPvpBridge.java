package net.shurui.shuruisutilities.compat.tournaments;

import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraftforge.fml.ModList;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// reflection bridge into Tournaments so SU's PvP vetoes know when a player is a live tournament fighter and
// must be hittable regardless of their consensual-PvP toggle. no compile/mods.toml edge on purpose, all
// reflection, gated on ModList. mod missing = always false, PvP falls back to the player's own toggle.
// same trick as RaidBossesCompat / TitleBridge. isActiveFighter is only true while state == MATCH and the
// player is in the live bout and not downed/eliminated, so it is inherently self-scoping and self-releasing:
// nothing is ever written to PlayerInfo or any UUID set here.
public final class TournamentPvpBridge
{
    private TournamentPvpBridge() {}

    private static final String TOURNAMENTS_MODID = "dmz_ragnarok";

    private static boolean resolved;
    private static Method managerGetM;      // TournamentManager.get() (static)
    private static Method isActiveFighterM; // TournamentManager.isActiveFighter(UUID)
    private static boolean warnedOnce;

    // is this player a fighter in a tournament match that's LIVE right now? false if tournaments is gone,
    // id is null, nothing's running, or reflection blows up. MUST fail closed so a missing/broken tournaments
    // never force-enables PvP.
    public static boolean isActiveFighter(UUID id)
    {
        // tournaments is now part of this same container; resolve() still fails soft if the API ever drifts.
        resolve();
        if (isActiveFighterM == null || id == null)
            return false;
        try
        {
            // get() is null before the server's up and after shutdown, so refetch every call.
            // caching it from resolve() time would hand you a stale/dead instance.
            Object mgr = managerGet();
            if (mgr == null)
                return false;
            Object r = isActiveFighterM.invoke(mgr, id);
            return r instanceof Boolean b && b;
        }
        catch (Throwable t)
        {
            warnOnce(t);
            return false;
        }
    }

    private static Object managerGet() throws Exception
    {
        return managerGetM == null ? null : managerGetM.invoke(null);
    }

    private static synchronized void resolve()
    {
        if (resolved)
            return;
        resolved = true;
        try
        {
            Class<?> c = Class.forName("net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentManager");
            managerGetM = c.getMethod("get");
            isActiveFighterM = c.getMethod("isActiveFighter", UUID.class);
        }
        catch (Throwable ignored)
        {
            managerGetM = null;
            isActiveFighterM = null;
        }
    }

    private static void warnOnce(Throwable t)
    {
        if (warnedOnce)
            return;
        warnedOnce = true;
        LoggingHandler.sulog.warn("Tournaments active-fighter query failed; tournament PvP force-enable disabled: "
                + t);
    }
}
