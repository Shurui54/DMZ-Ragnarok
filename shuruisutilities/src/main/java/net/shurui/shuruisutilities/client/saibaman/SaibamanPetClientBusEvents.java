package net.shurui.shuruisutilities.client.saibaman;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.saibaman.SaibamanPetEntities;

// client-only, mod-bus renderer registration for the tamed saibaman pet. Mirrors ShadowShenron / space-defender
// client wiring; gated to Dist.CLIENT so the renderer class never loads on a dedicated server.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SaibamanPetClientBusEvents
{
    private SaibamanPetClientBusEvents()
    {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerEntityRenderer(SaibamanPetEntities.SAIBAMAN_PET.get(), SaibamanPetRenderer::new);
    }
}
