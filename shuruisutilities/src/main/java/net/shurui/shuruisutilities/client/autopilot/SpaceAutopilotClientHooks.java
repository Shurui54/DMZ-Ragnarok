package net.shurui.shuruisutilities.client.autopilot;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


/**
 * Client-only lifecycle hook that drops the {@link SpaceAutopilotClient} state when the player leaves a server. The
 * autopilot state is authoritative server-side and pushed to the client only on change, so nothing would otherwise clear
 * a stale "active" course on disconnect: without this, relogging (or joining a different server) while an autopilot had
 * been running could leave the travel mixin ready to hijack the next space pod. Registered automatically on the FORGE
 * bus for the CLIENT dist only, so it never classloads on a dedicated server.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SpaceAutopilotClientHooks
{
    private SpaceAutopilotClientHooks()
    {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        SpaceAutopilotClient.clear();
    }
}
