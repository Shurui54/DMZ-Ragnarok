package net.shurui.shuruisutilities.client.space;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.space.SuperBallEntities;

// client-only, mod-bus renderer registration for the collectible super dragon ball entity. Mirrors the saibaman /
// space-defender client wiring; gated to Dist.CLIENT so the renderer class never loads on a dedicated server.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SuperBallClientBusEvents
{
    private SuperBallClientBusEvents()
    {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerEntityRenderer(SuperBallEntities.SUPER_BALL.get(), SuperBallRenderer::new);
    }
}
