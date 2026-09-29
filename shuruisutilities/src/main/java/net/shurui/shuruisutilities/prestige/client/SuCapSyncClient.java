package net.shurui.shuruisutilities.prestige.client;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.prestige.PacketSuCapSyncRequest;

import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// client-initiated recovery for the prestige stat-cap sync. server pushes PacketSuCapMult on a few occasions
// (login, prestige, slot switch, respawn, dim change), but that rides SU's channel with no ordering vs DMZ's
// stats sync and no periodic refresh; on Mohist a dropped/late copy leaves DMZ's stats screen computing
// headroom with a stale cap and never drawing the +stat buttons.
// so whenever DMZ's stats screen opens we fire PacketSuCapSyncRequest and the server re-pushes the
// multipliers. screen matched by class name to avoid hard-classloading a DMZ client class. once per open,
// no throttling. client only.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SuCapSyncClient
{
    private SuCapSyncClient() {}

    // matched by string to avoid hard-classloading a DMZ client class
    private static final String DMZ_STATS_SCREEN = "com.dragonminez.client.gui.character.CharacterStatsScreen";

    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event)
    {
        Screen screen = event.getNewScreen();
        if (screen == null || !DMZ_STATS_SCREEN.equals(screen.getClass().getName()))
            return;
        NetworkUtils.sendToServer(new PacketSuCapSyncRequest());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        // Leaving a server clears the cached prestige cap boost so a stale value can't bleed into the next
        // session (single-player or another server) and mis-gate the stat screen before the real sync arrives.
        SuCapClient.setPct(0);
        SuTpClient.set(0, 1.0);
    }
}
