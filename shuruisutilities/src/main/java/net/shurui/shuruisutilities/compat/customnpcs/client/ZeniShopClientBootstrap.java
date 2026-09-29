package net.shurui.shuruisutilities.compat.customnpcs.client;


import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

// registers the client shop hooks (ZeniShopClient) at client setup, only when CustomNPCs is present, so the
// noppes-referencing class is never loaded without it. server-side charging is wired from ModuleEconomy (in the
// Ragnarok Key).
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ZeniShopClientBootstrap
{
    private ZeniShopClientBootstrap() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        if (ModList.get().isLoaded("customnpcs")
                && net.shurui.shuruisutilities.core.config.Features.enabled(
                        net.shurui.shuruisutilities.core.config.Features.CUSTOMNPCS_ECONOMY))
            ZeniShopClient.register();
    }
}
