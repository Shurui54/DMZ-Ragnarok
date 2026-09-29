package net.shurui.shuruisutilities.compat.dmz;

import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Rebuilds the shadow dragon transformation state for everyone online, for when a server's Omega has stopped working.
 *
 * <p>Separate from the race-file refresh ({@code /rgrace refresh}), which rewrites the race JSON on disk. This is the
 * OTHER half: the per-player working state DMZ holds. A server can have perfectly correct race files and still have a
 * player whose {@code superforms} skill was never raised, which is what "Omega does not work" looks like from the
 * inside, and no amount of refreshing files fixes that on its own.
 *
 * <p>The per-player half (re-apply the Omega form skill to every online player who holds the entitlement) is the
 * Ragnarok Key's, feature {@code shadowform} ({@code ShadowFormHooks.repairOnline}); core keeps the report line and the
 * technique re-registration, which is public.
 */
public final class ShadowDragonRepair
{
    private ShadowDragonRepair() {}

    /** One line of the report, so the command can say what actually happened per player. */
    public static final class RepairLine
    {
        public final String player;
        public final String outcome;

        public RepairLine(String player, String outcome)
        {
            this.player = player;
            this.outcome = outcome;
        }
    }

    /**
     * Re-register the shadow dragon and role techniques into DMZ's registry.
     *
     * <p>Worth doing in the same command because a technique registry that lost our entries (a DMZ reload, a mod
     * that rebuilt it) presents exactly like a broken transformation: the moves are simply not there.
     */
    public static boolean reregisterTechniques()
    {
        if (!ModList.get().isLoaded("dragonminez"))
            return false;
        try
        {
            DragonTechniqueDefs.register();
            return true;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dragons] technique re-registration failed: {}", t.toString());
            return false;
        }
    }
}
