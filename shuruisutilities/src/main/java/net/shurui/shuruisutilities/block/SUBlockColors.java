package net.shurui.shuruisutilities.block;


import net.minecraft.world.item.DyeColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side tint for the colored portal blocks: the block's model uses tint index 0, and this handler
 * feeds it the RGB of the block's {@link ColoredPortalBlock#COLOR} property, so one model renders in any
 * of the 16 dye colors. Registered on the CLIENT dist / mod bus only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SUBlockColors
{
    private SUBlockColors() {}

    @SubscribeEvent
    public static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event)
    {
        // Only the nether variant is tinted; the end variant is drawn by its block-entity renderer.
        event.register((state, level, pos, tintIndex) -> {
            DyeColor c = state.getValue(ColoredPortalBlock.COLOR);
            float[] rgb = c.getTextureDiffuseColors();
            return (int) (rgb[0] * 255.0F) << 16 | (int) (rgb[1] * 255.0F) << 8 | (int) (rgb[2] * 255.0F);
        }, SUBlocks.COLORED_NETHER_PORTAL.get());
    }
}
