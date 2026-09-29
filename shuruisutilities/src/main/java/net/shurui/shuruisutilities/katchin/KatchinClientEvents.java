package net.shurui.shuruisutilities.katchin;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Client-only: registers the {@code shuruisutilities:colour} item property on the five katchi katchin tools so their
 * model {@code overrides} can select a texture by stored colour. The property returns 0.0 = blue_grey, 1.0 = orange,
 * 2.0 = cream, read from {@link KatchiKatchinColour} (whose NBT key is the same one the smithing randomiser writes).
 * A stack with no colour yet reads as the default (0.0), so it never renders a missing model.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class KatchinClientEvents
{
    private KatchinClientEvents() {}

    private static final ResourceLocation COLOUR_PROPERTY = new ResourceLocation(ShuruisUtilities.MODID, "colour");

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        event.enqueueWork(() ->
        {
            registerColour(KatchinItems.KATCHI_KATCHIN_PICKAXE.get());
            registerColour(KatchinItems.KATCHI_KATCHIN_AXE.get());
            registerColour(KatchinItems.KATCHI_KATCHIN_SHOVEL.get());
            registerColour(KatchinItems.KATCHI_KATCHIN_HOE.get());
            registerColour(KatchinItems.KATCHI_KATCHIN_HAMMER.get());
        });
    }

    private static void registerColour(Item item)
    {
        // Clamped 0..2; the stored colour is already clamped, and an absent value defaults to blue_grey.
        ItemProperties.register(item, COLOUR_PROPERTY,
                (stack, level, entity, seed) -> (float) KatchiKatchinColour.get(stack));
    }
}
