package net.shurui.shuruisutilities.events.client;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import net.shurui.shuruisutilities.content.ContentItems;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.events.item.EventTokenItem;

/**
 * Client-only: registers the {@code dmz_ragnarok:variant} item property on the single {@code event_token} item so
 * its model {@code overrides} select a texture straight from the NBT {@code Variant} (candy, candy corn, chocolate
 * dabura, chocolate dabura wrapped) without needing CustomModelData. The legacy CustomModelData override is kept in
 * the model, so an old candy stack that carries only CMD still renders as candy. An unknown or absent variant reads
 * 0.0 (the base model), so nothing ever renders a missing model.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class EventTokenClientEvents
{
    private EventTokenClientEvents() {}

    private static final ResourceLocation VARIANT_PROPERTY = new ResourceLocation(ShuruisUtilities.MODID, "variant");

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        event.enqueueWork(() -> ItemProperties.register(ContentItems.EVENT_TOKEN.get(), VARIANT_PROPERTY,
                (stack, level, entity, seed) -> (float) EventTokenItem.variantIndex(stack)));
    }
}
