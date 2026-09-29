package net.shurui.shuruisutilities.client.clone;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.clone.MiniCloneEntities;

// client-only, mod-bus renderer registration for the mini clone. Mirrors SaibamanPetClientBusEvents exactly: no explicit
// modid (in the merged jar every class belongs to dmz_ragnarok, and naming a pre-merge modid would register nothing and
// fail silently), gated to Dist.CLIENT so the renderer classes never load on a dedicated server.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MiniCloneClientBusEvents
{
    private MiniCloneClientBusEvents()
    {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerEntityRenderer(MiniCloneEntities.MINI_CLONE.get(), MiniCloneRenderer::new);
    }
}
