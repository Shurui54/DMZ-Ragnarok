package net.shurui.shuruisutilities.client.hud;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Clears the cached Shards balance when the client leaves a server.
 *
 * <p>Without this the figure from server A would still be in memory on server B, and on a server that does not
 * run the currency at all nothing would ever correct it, so a player would be shown a balance they do not have
 * there. Back to -1 means the element simply does not draw until the next server says otherwise.
 *
 * <p>The annotation is deliberately BARE, like {@code EnergyClientEvents}: the five original addons are one jar
 * now, so naming a modid here is a chance to name the wrong one, and a wrong modid on an EventBusSubscriber
 * fails SILENTLY.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShardClientEvents
{
    private ShardClientEvents() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        ShardClientCache.clear();
    }
}
