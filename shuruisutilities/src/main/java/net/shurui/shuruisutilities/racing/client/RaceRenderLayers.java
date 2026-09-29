package net.shurui.shuruisutilities.racing.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Adds the racing player render layers to the vanilla player renderers at mod-bus time. DragonMineZ forwards each
 * non-vanilla vanilla-renderer layer onto its geo player (see {@code CosmeticRenderClientBusEvents} for the same
 * pattern and the forwarder's constraints), so adding the aura outline here draws it on the DMZ player, self and
 * others. Client only, mod bus. The modid is explicit (the multi-mod jar defaults a bare one to every container).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RaceRenderLayers
{
    private RaceRenderLayers() {}

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event)
    {
        for (String skin : event.getSkins())
        {
            EntityRenderer<?> renderer = event.getSkin(skin);
            if (renderer instanceof PlayerRenderer player)
                player.addLayer(new RaceAuraOutlineLayer(player));
        }
    }
}
