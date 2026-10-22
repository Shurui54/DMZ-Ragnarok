package net.shurui.shuruisutilities.client.config;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.shurui.dev.sdu.config.editor.ConfigEditorRegistry;
import net.shurui.dev.sdu.config.editor.ConfigTabs;
import net.shurui.dev.sdu.config.editor.SimpleSource;
import net.shurui.shuruisutilities.client.cosmetics.CosmeticRenderOptions;
import net.shurui.shuruisutilities.client.cosmetics.pet.CosmeticPetClientOptions;

/**
 * SU's per-player options on the options editor's Client tab: the client-only values of main.toml and the cosmetic
 * display switches. Saved on this game client only, through each owner's own setter, never sent to a server.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ConfigEditorClientSources
{
    private ConfigEditorClientSources()
    {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        net.shurui.shuruisutilities.core.config.ConfigEditorSources.registerClient();
        ConfigEditorRegistry.addClient(new SimpleSource("su.cosmetics.client", "config/dmz_ragnarok_cosmetic_client.properties", null)
                .bool("showOthers", ConfigTabs.CLIENT, "Draw other players' cosmetics (/cosmetics others).",
                        CosmeticRenderOptions::showOthers, CosmeticRenderOptions::setShowOthers, true)
                .bool("hideHairUnderHelmets", ConfigTabs.CLIENT,
                        "An enclosing head cosmetic hides your DMZ hair (/cosmetichair).",
                        CosmeticRenderOptions::hideHairUnderHelmets, CosmeticRenderOptions::setHideHairUnderHelmets, true));
        ConfigEditorRegistry.addClient(new SimpleSource("su.pets.client", "config/dmz_ragnarok-pets.properties", null)
                .bool("hidden", ConfigTabs.CLIENT, "Hide cosmetic pets (everyone's, on this client only).",
                        CosmeticPetClientOptions::hidden, CosmeticPetClientOptions::setHidden, false));
    }
}
