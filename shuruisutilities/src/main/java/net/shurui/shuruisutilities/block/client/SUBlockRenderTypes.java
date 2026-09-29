package net.shurui.shuruisutilities.block.client;

import net.shurui.shuruisutilities.corrupted.CorruptedBalls;
import net.shurui.shuruisutilities.senzu.SenzuRegistry;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Forces the cutout render layer on every SU block whose baked model has hard-edged, partly transparent pixel art:
 * the bean pots (blank + eight typed) and the corrupted shrine. Those models also carry Forge's per-model
 * "render_type": "minecraft:cutout" field, which is enough on its own, but this block-wide registration is
 * unambiguous and covers each block no matter which model part is being drawn.
 *
 * <p>Cutout (not cutout_mipped, not translucent): these are hard-edged pixel-art sprites with fully on/off alpha, so
 * cutout is exactly right. Without it a block renders on the solid layer, which ignores alpha and draws every
 * transparent texel black. Client dist / mod bus only, work enqueued so it lands on the render thread.
 *
 * <p>The corrupted dragon balls are deliberately NOT registered here: their models parent "builtin/entity" and are
 * drawn by a custom block-entity renderer, so a block model render layer would not apply to them anyway.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SUBlockRenderTypes
{
    private SUBlockRenderTypes() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        event.enqueueWork(() ->
        {
            for (Block pot : SenzuRegistry.allPotBlocks())
            {
                ItemBlockRenderTypes.setRenderLayer(pot, RenderType.cutout());
            }
            ItemBlockRenderTypes.setRenderLayer(CorruptedBalls.CORRUPTED_SHRINE.get(), RenderType.cutout());
            // the saibaman seed crop: a cross-shaped, hard-edged pixel-art plant, same cutout need as the bean pots.
            ItemBlockRenderTypes.setRenderLayer(
                    net.shurui.shuruisutilities.saibaman.SaibamanCropRegistry.SAIBAMAN_CROP.get(), RenderType.cutout());
            // the shenron idol: its case is glass and the dragon inside is a cut-out plane, so both need the alpha
            // to be honoured rather than rendered as solid squares.
            ItemBlockRenderTypes.setRenderLayer(
                    net.shurui.shuruisutilities.ritual.ShenronIdol.BLOCK.get(), RenderType.cutout());
            // the rune bench: TRANSLUCENT, not cutout. Its gems are drawn at partial alpha (48 pixels between 165
            // and 224), and cutout rounds every pixel to fully on or fully off, which turned the glass into solid
            // stone. Translucent is the only layer that keeps them see-through.
            ItemBlockRenderTypes.setRenderLayer(
                    net.shurui.shuruisutilities.runes.RuneBenchRegistry.BENCH.get(), RenderType.translucent());
        });
    }
}
