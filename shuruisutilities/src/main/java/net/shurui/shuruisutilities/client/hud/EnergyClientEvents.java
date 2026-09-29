package net.shurui.shuruisutilities.client.hud;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Clears the cached role bar when the client leaves a server, so the last server's bar cannot draw on the next world
 * joined (single-player, or a server that never pushes a correction).
 *
 * <p>Unkeyed {@code @Mod.EventBusSubscriber} like {@code MainMenuClientEvents}: binds in the merged jar, where
 * handlers keyed to one of the five old modids fail silently.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EnergyClientEvents
{
    private EnergyClientEvents() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        EnergyClientCache.clear();
    }
}
