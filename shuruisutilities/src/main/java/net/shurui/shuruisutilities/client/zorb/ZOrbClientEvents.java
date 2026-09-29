package net.shurui.shuruisutilities.client.zorb;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.zorb.ZOrbEntities;

/**
 * Client wiring for the Z orb entity: binds its renderer on the mod bus. Z1 registers only the placeholder
 * {@link ZOrbRenderer} (Z3 swaps in the real translucent-shell renderer). Explicit {@code modid = "dmz_ragnarok"}
 * as the split requires.
 */
public final class ZOrbClientEvents
{
    private ZOrbClientEvents() {}

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus
    {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
        {
            event.registerEntityRenderer(ZOrbEntities.Z_ORB.get(), ZOrbRenderer::new);
        }
    }
}
