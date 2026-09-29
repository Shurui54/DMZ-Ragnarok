package net.shurui.shuruisutilities.compat;

import java.lang.reflect.Method;

import net.minecraftforge.fml.ModList;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// reflection bridge into raid bosses so the npcregion spawner won't drop npcs into a live raid arena.
// no compile/mods.toml edge on purpose, all reflection, gated on ModList. mod missing = always false, spawner
// acts like this file doesn't exist. same trick as ZSoulBridge.
public final class RaidBossesCompat
{
    private RaidBossesCompat() {}

    private static final String RAID_BOSSES_MODID = "dmz_ragnarok";

    private static boolean resolved;
    private static Method isInsideM;           // RaidManager.isInsideActiveRaidZone(String,double,double,double)
    private static Method isPlayerInActiveRaidM; // RaidManager.isPlayerInActiveRaid(UUID)
    private static boolean warnedOnce;

    // is (x,y,z) inside some raid that's actually fighting right now? false if raid bosses is gone,
    // nothing's running, or reflection blows up.
    public static boolean isInsideActiveRaidZone(String dimId, double x, double y, double z)
    {
        // raid bosses is now part of this same container; resolve() still fails soft if the API ever drifts.
        resolve();
        if (isInsideM == null)
            return false;
        try
        {
            // get() is null before the server's up and after shutdown, so refetch every call.
            // caching it from resolve() time would hand you a stale/dead instance.
            Object mgr = managerGet();
            if (mgr == null)
                return false;
            Object r = isInsideM.invoke(mgr, dimId, x, y, z);
            return r instanceof Boolean b && b;
        }
        catch (Throwable t)
        {
            warnOnce(t);
            return false;
        }
    }

    // is this player a participant in a raid whose boss fight is ACTIVE right now? false if raid bosses
    // is gone, id is null, nothing's running, or reflection blows up. lets the npcregion spawner skip
    // raiders as spawn anchors so their arena doesn't keep region cooldowns burning.
    public static boolean isPlayerInActiveRaid(java.util.UUID id)
    {
        // raid bosses is now part of this same container; resolve() still fails soft if the API ever drifts.
        resolve();
        if (isPlayerInActiveRaidM == null || id == null)
            return false;
        try
        {
            // get() is null before the server's up and after shutdown, so refetch every call.
            Object mgr = managerGet();
            if (mgr == null)
                return false;
            Object r = isPlayerInActiveRaidM.invoke(mgr, id);
            return r instanceof Boolean b && b;
        }
        catch (Throwable t)
        {
            warnOnce(t);
            return false;
        }
    }

    private static Method managerGetM; // RaidManager.get() (static)

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
            Class<?> c = Class.forName("net.shurui.dev.shuruis_raid_bosses.raid.RaidManager");
            managerGetM = c.getMethod("get");
            isInsideM = c.getMethod("isInsideActiveRaidZone",
                    String.class, double.class, double.class, double.class);
            isPlayerInActiveRaidM = c.getMethod("isPlayerInActiveRaid", java.util.UUID.class);
        }
        catch (Throwable ignored)
        {
            managerGetM = null;
            isInsideM = null;
            isPlayerInActiveRaidM = null;
        }
    }

    private static void warnOnce(Throwable t)
    {
        if (warnedOnce)
            return;
        warnedOnce = true;
        LoggingHandler.sulog.warn("Raid Bosses active-zone query failed; npcregion raid-zone suppression disabled: "
                + t);
    }
}
