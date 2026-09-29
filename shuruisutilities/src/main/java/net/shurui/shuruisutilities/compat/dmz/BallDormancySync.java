package net.shurui.shuruisutilities.compat.dmz;

import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.compat.dmz.network.PacketBallDormancySync;

/**
 * Server side sender for the dormant ball set list. Reads the authoritative state ({@link BallDormancyStorage}) and
 * pushes it to clients so the block renderer can grey a dormant set's balls. No DragonMineZ types are touched here,
 * so this is safe to call from anywhere on the server (login and dimension hooks and the mark/wake flip sites
 * alike). Mirrors {@link net.shurui.shuruisutilities.corrupted.DefiledBallsSync}.
 */
public final class BallDormancySync
{
    private BallDormancySync() {}

    /** Push the current dormant set list to a single player. No-op if networking or the server is not ready. */
    public static void sendTo(ServerPlayer player)
    {
        if (player == null || player.getServer() == null)
            return;
        try
        {
            List<String> sets = BallDormancy.dormantSets(player.getServer());
            NetworkUtils.sendTo(new PacketBallDormancySync(sets), player);
        }
        catch (Throwable ignored)
        {
        }
    }

    /** Push the current dormant set list to every online player. Called from the mark and wake flip sites. */
    public static void broadcast(MinecraftServer server)
    {
        if (server == null)
            return;
        try
        {
            List<String> sets = BallDormancy.dormantSets(server);
            for (ServerPlayer sp : server.getPlayerList().getPlayers())
                NetworkUtils.sendTo(new PacketBallDormancySync(sets), sp);
        }
        catch (Throwable ignored)
        {
        }
    }
}
