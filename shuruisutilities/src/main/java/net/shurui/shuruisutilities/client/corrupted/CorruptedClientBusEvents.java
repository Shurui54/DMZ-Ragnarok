package net.shurui.shuruisutilities.client.corrupted;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.block.SUBlockEntities;
import net.shurui.shuruisutilities.corrupted.ShadowShenronEntities;

/**
 * Client-only, mod-event-bus subscriptions for the swap balls (the GeckoLib block entity renderer). Gated to
 * {@link Dist#CLIENT} so the renderer classes are never loaded on a dedicated server, keeping the block
 * headless-safe. Mirrors sdu's ClientModBusEvents registration idiom.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CorruptedClientBusEvents
{
    private CorruptedClientBusEvents() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerBlockEntityRenderer(SUBlockEntities.CORRUPTED_BALL.get(), CorruptedBallBlockRenderer::new);
        // every SU crate draws through one renderer; the block says which art it wears.
        event.registerBlockEntityRenderer(SUBlockEntities.SU_CRATE.get(),
                c -> new net.shurui.shuruisutilities.client.crate.SuCrateRenderer());
        event.registerEntityRenderer(ShadowShenronEntities.SHADOW_SHENRON.get(), ShadowShenronRenderer::new);
    }
}
