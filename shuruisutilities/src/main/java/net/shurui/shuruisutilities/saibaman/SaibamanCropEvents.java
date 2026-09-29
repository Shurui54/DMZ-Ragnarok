package net.shurui.shuruisutilities.saibaman;

import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.entity.player.BonemealEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


/**
 * Forge-bus handler for the saibaman crop's bone-meal immunity. The crop grows ONLY on its own server ticker (via
 * {@link SaibamanCropBlockEntity}) with ki charging as the intended accelerator; leaving bone meal in would trivially
 * bypass that, so we DENY it, matching the senzu pots' policy.
 *
 * <p>The crop is not {@link net.minecraft.world.level.block.BonemealableBlock}, so vanilla bone meal already does
 * nothing. But Forge fires {@link BonemealEvent} BEFORE that check, so a third-party mod bone-mealing through the event
 * could otherwise force a stage. Denying the event for our crop closes that gap.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SaibamanCropEvents
{
    private SaibamanCropEvents() {}

    @SubscribeEvent
    public static void onBonemeal(BonemealEvent event)
    {
        Block block = event.getBlock().getBlock();
        if (block instanceof SaibamanCropBlock)
        {
            event.setResult(Event.Result.DENY);
        }
    }
}
