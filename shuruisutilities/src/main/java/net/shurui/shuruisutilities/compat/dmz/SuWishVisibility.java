package net.shurui.shuruisutilities.compat.dmz;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.SyncWishesS2C;
import com.dragonminez.common.wish.DragonWishRegistry;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.ritual.WishRitualManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Keeps each player's wish list showing only the wishes they can actually take.
 *
 * <p>DragonMineZ syncs one wish map to everybody. That is right for its own wishes and wrong for a wish whose whole
 * point is that it appears when you have earned it, so this sends a per player copy over the top.
 *
 * <h2>Why LOWEST priority</h2>
 * DMZ syncs the shared map from its own handler on the same event at default priority. Running before it would just be
 * overwritten a moment later. Running after means our copy is the last one the client receives, and since the packet
 * replaces the client's map wholesale rather than merging, last writer wins.
 *
 * <h2>Why it also polls</h2>
 * Eligibility is not fixed at login: a saiyan transforms, levels up, and becomes able to take the wish mid session,
 * and the wish screen is drawn from whatever was last synced. Rather than trying to hook every event that could change
 * the answer, this asks the question a few times a second and only sends a packet when the answer has CHANGED, which
 * costs one boolean comparison per player per check and sends nothing at all in the normal case.
 */
public final class SuWishVisibility
{
    // How often eligibility is re-checked, in server ticks. Two seconds: fast enough that a player who transforms and
    // walks to the dragon finds the wish waiting, slow enough to be free.
    private static final int CHECK_INTERVAL_TICKS = 40;

    // The visibility state we last told each player, so nothing is sent while the answer is unchanged. Two bits: the
    // SSJ5 row and the SSG knowledge row are trimmed independently within the coupled suffix, so both have to be part
    // of the change signal or a saiyan who crosses the SSG level floor without touching SSJ5 would never be resynced.
    private static final Map<UUID, Integer> LAST_SENT = new HashMap<>();

    private int counter;
    private boolean loggedFailure;

    /** After DMZ has synced the shared map, replace it with each player's own view of it. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDatapackSync(OnDatapackSyncEvent event)
    {
        try
        {
            if (event.getPlayer() != null)
            {
                send(event.getPlayer());
                return;
            }
            for (ServerPlayer player : event.getPlayerList().getPlayers())
            {
                send(player);
            }
        }
        catch (Throwable t)
        {
            report(t);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (++counter < CHECK_INTERVAL_TICKS)
            return;
        counter = 0;
        try
        {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null)
            {
                LAST_SENT.clear();
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers())
            {
                Integer last = LAST_SENT.get(player.getUUID());
                if (last == null || last != visibilityState(player))
                    send(player);
            }
        }
        catch (Throwable t)
        {
            report(t);
        }
    }

    private void send(ServerPlayer player)
    {
        if (player == null || NetworkHandler.INSTANCE == null)
            return;
        NetworkHandler.sendToPlayer(
                new SyncWishesS2C(SuSsj5Wish.visibleTo(DragonWishRegistry.getServerWishes(), player)), player);
        LAST_SENT.put(player.getUUID(), visibilityState(player));
    }

    // The two-bit signal that decides whether a resend is due: bit 1 the SSJ5 row, bit 0 the SSG knowledge row. Kept
    // in step with what SuSsj5Wish.visibleTo actually trims, so a change in either row triggers exactly one resend.
    private static int visibilityState(ServerPlayer player)
    {
        int state = 0;
        if (WishRitualManager.canWishForSsj5(player))
            state |= 2;
        if (WishRitualManager.ssgWishVisible(player))
            state |= 1;
        if (net.shurui.shuruisutilities.wish.SuperPowerWishCommand.exhausted(player))
            state |= 4;
        return state;
    }

    // One shot, per the standing rule for DMZ internals: a drifted API costs the per player filtering, not the sync.
    private void report(Throwable t)
    {
        if (loggedFailure)
            return;
        loggedFailure = true;
        LoggingHandler.sulog.warn("[ritual] Could not send per player wish visibility; every player will see the "
                + "shared wish list instead. Cause: {}", t.toString());
    }
}
