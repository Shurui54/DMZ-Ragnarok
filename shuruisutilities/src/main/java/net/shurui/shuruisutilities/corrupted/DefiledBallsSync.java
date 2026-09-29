package net.shurui.shuruisutilities.corrupted;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.corrupted.network.PacketDefiledSync;

/**
 * Server-side sender for the defiled-balls flag. Reads the authoritative state
 * ({@code ShadowDragonStorage.hasDefiledBallsPresent}) and pushes it to clients so the Earth radar can swap to the
 * shadow-dragon dial while defiled balls are physically out in the world. No DragonMineZ types are touched here,
 * so this is safe to call from anywhere on the server (login/dimension hooks and the arm/disarm flip sites alike).
 *
 * <p>Two entry points, both event-driven so there is no per-tick polling: {@link #sendTo(ServerPlayer)} on the login and
 * dimension-change hooks, and {@link #broadcast(MinecraftServer)} the moment the state flips.
 */
public final class DefiledBallsSync
{
    private DefiledBallsSync() {}

    /** The authoritative Earth flag: defiled while corrupted balls are actually scattered in the world (armed). */
    public static boolean isEarthDefiled(MinecraftServer server)
    {
        if (server == null)
            return false;
        try
        {
            return ShadowDragonStorage.get(server).hasDefiledBallsPresent("earth");
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /**
     * The authoritative Namek flag. Namek never has defiled balls in the world (the corrupted event scatters over the
     * overworld only), so this is always false and Namek's dial stays on DMZ's stock art.
     */
    public static boolean isNamekDefiled(MinecraftServer server)
    {
        if (server == null)
            return false;
        try
        {
            return ShadowDragonStorage.get(server).hasDefiledBallsPresent("namek");
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /** Push the current per-set defiled flags to a single player. No-op if networking or the server is not ready. */
    public static void sendTo(ServerPlayer player)
    {
        if (player == null || player.getServer() == null)
            return;
        try
        {
            MinecraftServer server = player.getServer();
            NetworkUtils.sendTo(new PacketDefiledSync(isEarthDefiled(server), isNamekDefiled(server)), player);
        }
        catch (Throwable ignored)
        {
        }
    }

    /** Push the current per-set defiled flags to every online player. Called from the arm / disarm / reset flip sites. */
    public static void broadcast(MinecraftServer server)
    {
        if (server == null)
            return;
        try
        {
            boolean earth = isEarthDefiled(server);
            boolean namek = isNamekDefiled(server);
            for (ServerPlayer sp : server.getPlayerList().getPlayers())
                NetworkUtils.sendTo(new PacketDefiledSync(earth, namek), sp);
        }
        catch (Throwable ignored)
        {
        }
    }
}
