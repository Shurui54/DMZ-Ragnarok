package net.shurui.shuruisutilities.compat.tournaments;

import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

// reflection bridge to Tournaments' title system so admin resets can clear a player's earned tournament titles.
// no hard dep; no-ops when Tournaments isn't installed.
public final class TitleBridge
{
    private TitleBridge() {}

    // clear all the player's tournament titles. no-op without Tournaments / with no server.
    public static void reset(ServerPlayer player)
    {
        resolve();
        if (clearAllM == null)
            return;
        MinecraftServer server = player.getServer();
        if (server == null)
            return;
        try
        {
            clearAllM.invoke(null, server, player.getUUID());
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * The title id this player currently holds, or null when they hold none / Tournaments is absent.
     *
     * <p>Used by the role energy layer to decide which bar a player has access to. Reads through
     * {@code TitleManager.heldTitle}, which resolves from the stored holder map rather than the title config, so a
     * role keeps working even if its definition has gone missing from the config file.
     */
    public static String heldTitle(ServerPlayer player)
    {
        resolve();
        if (heldTitleM == null || player == null)
            return null;
        MinecraftServer server = player.getServer();
        if (server == null)
            return null;
        try
        {
            Object result = heldTitleM.invoke(null, server, player.getUUID());
            return result instanceof String s ? s : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static boolean resolved;
    private static Method clearAllM;  // TitleManager.clearAll(MinecraftServer, UUID) -> int
    private static Method heldTitleM; // TitleManager.heldTitle(MinecraftServer, UUID) -> String

    private static synchronized void resolve()
    {
        if (resolved)
            return;
        resolved = true;
        try
        {
            Class<?> c = Class.forName("net.shurui.dev.shuruis_dmz_tournaments.reward.TitleManager");
            clearAllM = c.getMethod("clearAll", MinecraftServer.class, UUID.class);
            // Looked up separately so a build where only one of the two exists still gets the other, rather than
            // both going null on the first NoSuchMethodException.
            try
            {
                heldTitleM = c.getMethod("heldTitle", MinecraftServer.class, UUID.class);
            }
            catch (Throwable ignored)
            {
                heldTitleM = null;
            }
        }
        catch (Throwable ignored)
        {
            clearAllM = null;
            heldTitleM = null;
        }
    }
}
