package net.shurui.shuruisutilities.client.gravitychamber;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.gravitychamber.ChamberSparEntities;

/**
 * Client-only, mod-event-bus registration of the {@link ChamberSparRenderer} for the guild-chamber sparring dummy.
 * Loads only on the client (a dedicated server never registers renderers).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ChamberSparClientEvents
{
    private ChamberSparClientEvents()
    {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerEntityRenderer(ChamberSparEntities.CHAMBER_SPAR.get(), ChamberSparRenderer::new);
    }
}
