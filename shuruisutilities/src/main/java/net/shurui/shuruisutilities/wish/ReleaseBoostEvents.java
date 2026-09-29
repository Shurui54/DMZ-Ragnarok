package net.shurui.shuruisutilities.wish;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Keeps a client's cached ki release bonus correct across sessions. On login the server pushes the player's stored
 * total ({@link ReleaseBoostStore}) so {@link ReleaseBoostClient} starts from the right number and the radial release
 * node draws the ceiling the server actually enforces. Without this, a player who earned the bonus in a previous
 * session would see the bare DMZ ceiling until their next wish.
 */
public final class ReleaseBoostEvents
{
    @SubscribeEvent
    public void onLogin(PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            NetworkUtils.sendTo(new PacketReleaseBoost(ReleaseBoostStore.bonus(player)), player);
        }
    }

    /** Drop the cached bonus so the store's map only ever holds players who are actually online. */
    @SubscribeEvent
    public void onLogout(PlayerLoggedOutEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            ReleaseBoostStore.forget(player);
        }
    }
}
