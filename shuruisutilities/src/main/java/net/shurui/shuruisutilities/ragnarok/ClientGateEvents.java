package net.shurui.shuruisutilities.ragnarok;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.dev.sdu.client.ClientWaypoints;
import net.shurui.shuruisutilities.client.hud.StaffTaskHudState;
import net.shurui.shuruisutilities.client.hud.ZeniClientCache;
import net.shurui.shuruisutilities.compat.client.NpcRegionCacheClient;
import net.shurui.shuruisutilities.ranks.client.RankClientCache;
import net.shurui.shuruisutilities.tablist.client.TabListBannerClient;

/**
 * Resets every client-side gate and cache when the client leaves a server (and, defensively, when it joins one),
 * so nothing from one server ever lingers into the next server or the main menu.
 *
 * <p>The {@link ClientGate} defaults to LOCKED, so a private feature is hidden until the login sync opens it. That
 * only works if the gate is put BACK to locked on disconnect: without this, a client that played on a keyed server
 * would keep the key flag open on the menu and on the next (keyless) server until its sync arrived, flashing private
 * UI in between. The stale content caches (Zeni, rank badges, NPC regions, the tab banner, waypoints, the staff task
 * HUD) are cleared for the same reason: they are server state the client should not carry across a disconnect.
 *
 * <p>Why also reset on {@code LoggingIn}: in Forge 1.20.1 {@code ClientPlayerNetworkEvent.LoggingIn} fires from
 * {@code ClientPacketListener.handleLogin} when the LocalPlayer is first created, which is BEFORE the server's
 * {@code PlayerLoggedInEvent} runs and sends our key/module sync packets. Those packets travel after the login
 * packet and are handled after this event, so resetting here cannot wipe a sync that already arrived: it only
 * guarantees a clean slate before the fresh sync lands. Resetting on both edges is belt-and-suspenders; the log
 * line is emitted only on logout, where "reset" is unambiguous.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class ClientGateEvents
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientGateEvents() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event)
    {
        resetAll();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        resetAll();
        LOGGER.info("[dmz_ragnarok] client gate reset (logout)");
    }

    private static void resetAll()
    {
        ClientGate.reset();
        ZeniClientCache.clear();
        RankClientCache.clear();
        NpcRegionCacheClient.clear();
        TabListBannerClient.reset();
        ClientWaypoints.clear();
        StaffTaskHudState.clear();
        net.shurui.shuruisutilities.racing.client.RaceClientState.reset();
        // Token buffs (key feature tokenbuffs): a keyed server's running gems must not price the next server's stats.
        net.shurui.dev.sdu.client.TokenBuffClient.clear();
    }
}
