package net.shurui.shuruisutilities.block.client;

import net.shurui.shuruisutilities.block.SUBlockEntities;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Binds SU's block entity renderers. Client dist / mod bus only. */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SUBlockEntityRenderers
{
    private SUBlockEntityRenderers() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerBlockEntityRenderer(SUBlockEntities.END_PORTAL.get(), EndPortalPlaneRenderer::new);
        // No renderer for the shenron idol: it draws from a plain block model now, and binding one here would draw
        // the statue a second time on top of it.
    }
}
