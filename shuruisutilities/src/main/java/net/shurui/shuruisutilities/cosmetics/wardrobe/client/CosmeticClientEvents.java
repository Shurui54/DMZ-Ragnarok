package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Clears the client's cosmetic cache when it leaves a server, and reports the held cosmetic art pack on join.
 *
 * <p>Without this, the catalogue and the outfit table from one server would still be in memory on the next, so a
 * cosmetic defined on server A could be resolved on server B until the first sync landed, and other players
 * would be drawn wearing whatever they had on somewhere else. Both stores are full replacements on login, so
 * this only closes the window between joining and the first packet.
 *
 * <p>The annotation is deliberately BARE: the five original addons are one jar now, so naming a modid here is a
 * chance to name the wrong one, and a wrong modid on an EventBusSubscriber fails SILENTLY.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class CosmeticClientEvents
{
    private CosmeticClientEvents()
    {
    }

    /**
     * Tell the server which cosmetic art pack we already hold, so the Ragnarok Key sends only what is missing (usually
     * nothing). Sent unprompted, as the rgnpc pack's report is. A keyless server ignores it and sends nothing.
     */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event)
    {
        CosmeticAssetCache.acknowledge();
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        CosmeticClientStore.clear();
    }
}
