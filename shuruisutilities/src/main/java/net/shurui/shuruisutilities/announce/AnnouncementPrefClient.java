package net.shurui.shuruisutilities.announce;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.SUConfig;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client side: tell the server this client's suite-announcement preference on every login.
 *
 * <p>Sent unprompted on join, which is what makes the whole design shard safe with nothing persisted: the preference
 * lives only in the client's COMMON SUConfig (Forge never syncs a COMMON config), and by re-announcing on every join
 * the client carries it to whatever shard it lands on. The toggle flip case is handled at the point of the flip, in
 * the DMZ config menu row, so this handler covers only the login re-send. See AnnouncementPrefs for the reasoning.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AnnouncementPrefClient
{
    private AnnouncementPrefClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event)
    {
        NetworkUtils.sendToServer(new PacketAnnouncementPref(SUConfig.suiteAnnouncements));
    }
}
